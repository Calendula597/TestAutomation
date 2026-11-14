package io.github.calendula597.testplatform.platformserver.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeChunkMapper;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeVersionMapper;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.testcase.TestCase;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseMapper;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseStep;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseStepMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the controlled ReAct loop for draft generation: the model may only
 * call whitelist tools, must finish with structured draft cases, and every
 * citation is validated against actually retrieved knowledge versions.
 */
@Service
public class DraftGenerationService {

    private static final int MAX_REACT_ROUNDS = 6;

    private final AgentService agentService;
    private final AgentTools agentTools;
    private final AgentSessionMapper sessionMapper;
    private final DraftBatchMapper batchMapper;
    private final DraftBatchCaseMapper batchCaseMapper;
    private final AgentToolCallMapper toolCallMapper;
    private final TestCaseMapper testCaseMapper;
    private final TestCaseStepMapper stepMapper;
    private final ObjectMapper objectMapper;

    public DraftGenerationService(AgentService agentService, AgentTools agentTools,
                                  AgentSessionMapper sessionMapper, DraftBatchMapper batchMapper,
                                  DraftBatchCaseMapper batchCaseMapper, AgentToolCallMapper toolCallMapper,
                                  TestCaseMapper testCaseMapper, TestCaseStepMapper stepMapper,
                                  ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.agentTools = agentTools;
        this.sessionMapper = sessionMapper;
        this.batchMapper = batchMapper;
        this.batchCaseMapper = batchCaseMapper;
        this.toolCallMapper = toolCallMapper;
        this.testCaseMapper = testCaseMapper;
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
    }

    private record DraftCaseInput(String title, String priority, String technique, String suggestion,
                                  boolean knowledgeInsufficient, Long duplicateOfCaseId,
                                  List<CitationInput> citations, List<StepInput> steps) {
    }

    private record CitationInput(long documentId, int version) {
    }

    private record StepInput(String action, String testData, String expected) {
    }

    @Transactional
    public DraftBatch generate(AgentSession session) {
        DraftBatch batch = new DraftBatch();
        batch.setSessionId(session.getId());
        batch.setProjectId(session.getProjectId());
        batch.setRequirementId(session.getRequirementId());
        batch.setStatus(DraftBatch.STATUS_PENDING_REVIEW);
        batch.setModel(session.getModel());
        batch.setPromptVersion(DraftBatch.PROMPT_VERSION);
        batchMapper.insert(batch);

        AgentService.RequirementContext requirement = agentService.loadRequirement(
                session.getProjectId(), session.getRequirementId());
        Set<AgentTools.CitedKnowledge> retrievalPool = new HashSet<>();

        List<Map<String, String>> history = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;
        List<DraftCaseInput> finalCases = null;

        for (int round = 1; round <= MAX_REACT_ROUNDS && finalCases == null; round++) {
            if (isCancelled(session)) {
                return failed(batch, "生成已取消", promptTokens, completionTokens);
            }
            String userPrompt = buildRoundPrompt(requirement, retrievalPool, history, round);
            ModelClient.Completion completion = agentService.callModel(userPrompt);
            promptTokens += completion.promptTokens();
            completionTokens += completion.completionTokens();

            JsonNode response = readJson(completion.content());
            if (response.has("final")) {
                finalCases = parseFinalCases(response.get("final"), retrievalPool, session.getProjectId());
            } else {
                JsonNode action = response.path("action");
                String tool = action.path("tool").asText("");
                String input = action.path("input").asText("");
                if (!agentTools.isWhitelisted(tool)) {
                    recordToolCall(batch.getId(), tool, input, "拒绝：白名单外工具", round);
                    history.add(Map.of("thought", response.path("thought").asText(),
                            "action", tool, "observation", "拒绝：白名单外工具"));
                    continue;
                }
                AgentTools.ToolObservation observation = agentTools.execute(
                        tool, input, session.getProjectId(), requirement, retrievalPool);
                observation.retrievedKnowledge().forEach(retrievalPool::add);
                recordToolCall(batch.getId(), tool, input,
                        truncate(observation.output(), 400), round);
                history.add(Map.of("thought", response.path("thought").asText(),
                        "action", tool + ": " + input, "observation",
                        truncate(observation.output(), 1200)));
            }
        }

        if (finalCases == null) {
            return failed(batch, "ReAct 轮次耗尽仍未产出草稿", promptTokens, completionTokens);
        }
        persistCases(batch.getId(), finalCases, retrievalPool);
        batch.setPromptTokens(promptTokens);
        batch.setCompletionTokens(completionTokens);
        batchMapper.updateById(batch);
        return batch;
    }

    private String buildRoundPrompt(AgentService.RequirementContext requirement,
                                    Set<AgentTools.CitedKnowledge> pool, List<Map<String, String>> history,
                                    int round) {
        StringBuilder sb = new StringBuilder();
        sb.append("需求：").append(requirement.title()).append("\n\n");
        sb.append("已确认的覆盖点与澄清回答见会话记录。\n\n");
        sb.append(AgentTools.whitelistDescription()).append("\n\n");
        sb.append("引用规则：final 中的 citations 只能引用上面工具返回过的 documentId 与其真实 version。\n\n");
        if (!history.isEmpty()) {
            sb.append("之前的推理轮次：\n");
            for (Map<String, String> entry : history) {
                sb.append("- 思考：").append(entry.get("thought")).append("\n  动作：")
                        .append(entry.get("action")).append("\n  观察：")
                        .append(truncate(entry.get("observation"), 500)).append("\n");
            }
        }
        sb.append("\n第 ").append(round).append(" 轮。输出 JSON：\n")
                .append("{\"thought\":\"...\",\"action\":{\"tool\":\"...\",\"input\":\"...\"}}\n或（完成时）：\n")
                .append("{\"final\":{\"draftCases\":[{\"title\":\"...\",\"priority\":\"HIGH|MEDIUM|LOW\",")
                .append("\"technique\":\"...\",\"suggestion\":\"NEW|DUPLICATE|UPDATE\",\"duplicateOfCaseId\":null,")
                .append("\"knowledgeInsufficient\":false,\"citations\":[{\"documentId\":1,\"version\":1}],")
                .append("\"steps\":[{\"action\":\"...\",\"testData\":\"...\",\"expected\":\"...\"}]}]}}");
        return sb.toString();
    }

    private JsonNode readJson(String content) {
        try {
            String json = content;
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ModelClient.ModelException("模型 ReAct 输出无法解析", e);
        }
    }

    private List<DraftCaseInput> parseFinalCases(JsonNode finalNode, Set<AgentTools.CitedKnowledge> pool,
                                                 Long projectId) {
        List<DraftCaseInput> cases = new ArrayList<>();
        ArrayNode draftCases = (ArrayNode) finalNode.withArray("draftCases");
        Set<String> validKeys = pool.stream()
                .map(k -> k.documentId() + ":" + k.version())
                .collect(Collectors.toSet());

        for (JsonNode node : draftCases) {
            String title = node.path("title").asText("");
            JsonNode stepsNode = node.withArray("steps");
            if (title.isBlank() || title.length() > 200) {
                throw new ModelClient.ModelException("草稿标题缺失或超长", null);
            }
            if (stepsNode.isEmpty()) {
                throw new ModelClient.ModelException("草稿「" + title + "」没有步骤", null);
            }
            List<CitationInput> citations = new ArrayList<>();
            for (JsonNode citation : node.withArray("citations")) {
                long documentId = citation.path("documentId").asLong();
                int version = citation.path("version").asInt(1);
                if (!validKeys.contains(documentId + ":" + version)) {
                    throw new ModelClient.ModelException(
                            "引用校验失败：documentId=" + documentId + " version=" + version
                                    + " 未在本次检索中出现（引用必须来自真实检索结果）", null);
                }
                citations.add(new CitationInput(documentId, version));
            }
            List<StepInput> steps = new ArrayList<>();
            stepsNode.forEach(s -> steps.add(new StepInput(s.path("action").asText(),
                    s.path("testData").asText(""), s.path("expected").asText(""))));
            for (StepInput step : steps) {
                if (step.action().isBlank() || step.expected().isBlank()) {
                    throw new ModelClient.ModelException("草稿「" + title + "」存在空的步骤或预期", null);
                }
            }
            cases.add(new DraftCaseInput(title, node.path("priority").asText("MEDIUM"),
                    node.path("technique").asText(""), node.path("suggestion").asText("NEW"),
                    node.path("knowledgeInsufficient").asBoolean(false),
                    node.hasNonNull("duplicateOfCaseId") ? node.path("duplicateOfCaseId").asLong() : null,
                    citations, steps));
        }
        if (cases.isEmpty()) {
            throw new ModelClient.ModelException("模型未返回任何草稿用例", null);
        }
        return cases;
    }

    private void persistCases(Long batchId, List<DraftCaseInput> cases,
                              Set<AgentTools.CitedKnowledge> pool) {
        for (DraftCaseInput input : cases) {
            DraftBatchCase row = new DraftBatchCase();
            row.setBatchId(batchId);
            row.setTitle(input.title());
            row.setPriority(input.priority());
            row.setTechnique(input.technique());
            row.setSuggestion(input.suggestion());
            row.setDuplicateOfCaseId(input.duplicateOfCaseId());
            row.setKnowledgeInsufficient(input.knowledgeInsufficient());
            try {
                ArrayNode steps = objectMapper.createArrayNode();
                for (StepInput step : input.steps()) {
                    ObjectNode stepNode = objectMapper.createObjectNode();
                    stepNode.put("action", step.action());
                    stepNode.put("testData", step.testData());
                    stepNode.put("expected", step.expected());
                    steps.add(stepNode);
                }
                row.setStepsJson(steps.toString());
                ArrayNode citations = objectMapper.createArrayNode();
                for (CitationInput citation : input.citations()) {
                    ObjectNode citationNode = objectMapper.createObjectNode();
                    citationNode.put("documentId", citation.documentId());
                    citationNode.put("version", citation.version());
                    pool.stream()
                            .filter(k -> k.documentId() == citation.documentId()
                                    && k.version() == citation.version())
                            .findFirst()
                            .ifPresent(k -> citationNode.put("documentTitle", k.title()));
                    citations.add(citationNode);
                }
                row.setCitationsJson(citations.toString());
            } catch (Exception e) {
                throw new IllegalStateException("草稿序列化失败", e);
            }
            batchCaseMapper.insert(row);
        }
    }

    private void recordToolCall(Long batchId, String tool, String input, String output, int round) {
        AgentToolCall call = new AgentToolCall();
        call.setBatchId(batchId);
        call.setToolName(tool);
        call.setInputSummary(truncate(input, 400));
        call.setOutputSummary(truncate(output, 400));
        toolCallMapper.insert(call);
    }

    private boolean isCancelled(AgentSession session) {
        AgentSession current = sessionMapper.selectById(session.getId());
        return current != null && AgentSession.STATUS_CANCELLED.equals(current.getStatus());
    }

    private DraftBatch failed(DraftBatch batch, String message, int promptTokens, int completionTokens) {
        batch.setStatus(DraftBatch.STATUS_FAILED);
        batch.setError(message);
        batch.setPromptTokens(promptTokens);
        batch.setCompletionTokens(completionTokens);
        batchMapper.updateById(batch);
        return batch;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
