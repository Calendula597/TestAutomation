package io.github.calendula597.testplatform.platformserver.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeChunk;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeChunkMapper;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeChunker;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.requirement.Requirement;
import io.github.calendula597.testplatform.platformserver.requirement.RequirementMapper;
import io.github.calendula597.testplatform.platformserver.requirement.RequirementVersion;
import io.github.calendula597.testplatform.platformserver.requirement.RequirementVersionMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent analysis building blocks: authorized requirement/knowledge context,
 * prompt assembly, strict JSON parsing and quota checks. Session/task
 * persistence lives in AgentOrchestrationService.
 */
@Service
public class AgentService {

    static final int MAX_KNOWLEDGE_CHUNKS = 5;

    private final RequirementMapper requirementMapper;
    private final RequirementVersionMapper requirementVersionMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeChunker chunker;
    private final ModelClient modelClient;
    private final int maxSessionsPerProject;

    public AgentService(RequirementMapper requirementMapper,
                        RequirementVersionMapper requirementVersionMapper,
                        KnowledgeChunkMapper chunkMapper, KnowledgeChunker chunker,
                        ModelClient modelClient,
                        @org.springframework.beans.factory.annotation.Value(
                                "${app.agent.max-sessions-per-project:5}") int maxSessionsPerProject) {
        this.requirementMapper = requirementMapper;
        this.requirementVersionMapper = requirementVersionMapper;
        this.chunkMapper = chunkMapper;
        this.chunker = chunker;
        this.modelClient = modelClient;
        this.maxSessionsPerProject = maxSessionsPerProject;
    }

    public record RequirementContext(String title, String body, String acceptanceCriteria) {
    }

    public record ScopedKnowledge(List<String> chunks) {
    }

    public record AnalysisOutput(List<ClarifiedQuestion> clarifications, List<ProposedPoint> coveragePoints) {
    }

    public record ClarifiedQuestion(String question, boolean blocking) {
    }

    public record ProposedPoint(String description, String technique) {
    }

    public RequirementContext loadRequirement(Long projectId, Long requirementId) {
        Requirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null || !requirement.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("需求不存在");
        }
        RequirementVersion current = requirementVersionMapper.selectOne(
                new LambdaQueryWrapper<RequirementVersion>()
                        .eq(RequirementVersion::getRequirementId, requirementId)
                        .eq(RequirementVersion::getVersion, requirement.getCurrentVersion()));
        if (current == null) {
            throw new ResourceNotFoundException("需求版本不存在");
        }
        return new RequirementContext(requirement.getTitle(), current.getBody(),
                current.getAcceptanceCriteria());
    }

    /** Keyword retrieval strictly inside global + the project's own scope. */
    public ScopedKnowledge retrieveKnowledge(String query, Long projectId) {
        String like = "%" + query.replace("%", "\\%") + "%";
        List<KnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .apply("content ILIKE {0}", like)
                        .apply("version_id IN (SELECT kv.id FROM knowledge_version kv "
                                + "JOIN knowledge_document kd ON kd.id = kv.document_id "
                                + "WHERE kd.scope = 'GLOBAL' OR kd.project_id = " + projectId + ")")
                        .last("LIMIT " + MAX_KNOWLEDGE_CHUNKS));
        List<String> contents = chunks.stream().map(KnowledgeChunk::getContent).toList();
        return new ScopedKnowledge(contents);
    }

    public String buildAnalysisPrompt(RequirementContext requirement, ScopedKnowledge knowledge) {
        return "需求标题：" + requirement.title()
                + "\n\n需求正文：\n" + requirement.body()
                + "\n\n验收标准：\n" + requirement.acceptanceCriteria()
                + "\n\n授权知识（只可基于以下内容，不得虚构）：\n"
                + String.join("\n---\n", knowledge.chunks());
    }

    public static final String ANALYSIS_SYSTEM_PROMPT = """
            你是测试设计助手。分析需求后输出严格 JSON（不要输出任何 JSON 之外的文本），结构：
            {"clarifications":[{"question":"...","blocking":true|false}],
             "coveragePoints":[{"description":"...","technique":"等价类|边界值|异常场景|权限场景|正向场景"}]}
            关键歧义（blocking=true）必须提问；次要假设作为 coveragePoints 列出并由用户确认。
            """;

    public AnalysisOutput parseAnalysis(String modelOutput) {
        try {
            String json = modelOutput;
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            tools.jackson.databind.JsonNode node = new tools.jackson.databind.ObjectMapper().readTree(json);
            List<ClarifiedQuestion> questions = new ArrayList<>();
            node.withArray("clarifications").forEach(q -> questions.add(new ClarifiedQuestion(
                    q.path("question").asText(), q.path("blocking").asBoolean(true))));
            List<ProposedPoint> points = new ArrayList<>();
            node.withArray("coveragePoints").forEach(p -> points.add(new ProposedPoint(
                    p.path("description").asText(), p.path("technique").asText(""))));
            if (questions.isEmpty() && points.isEmpty()) {
                throw new ModelClient.ModelException("模型未返回任何澄清或覆盖点", null);
            }
            return new AnalysisOutput(questions, points);
        } catch (ModelClient.ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelClient.ModelException("模型输出无法解析为结构化 JSON", e);
        }
    }

    public ModelClient.Completion callModel(String prompt) {
        return modelClient.complete(ANALYSIS_SYSTEM_PROMPT, prompt);
    }

    public void assertQuota(long activeSessions, long projectId) {
        if (activeSessions >= maxSessionsPerProject) {
            throw new QuotaExceededException("该项目进行中的生成会话已达上限（" + maxSessionsPerProject + "）");
        }
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }

    /** Detects duplicate-like coverage points against existing descriptions. */
    public boolean isDuplicate(CoveragePoint candidate, List<CoveragePoint> existing) {
        return existing.stream().anyMatch(p ->
                p.getId() != null && !p.getId().equals(candidate.getId())
                        && p.getDescription() != null
                        && p.getDescription().equals(candidate.getDescription()));
    }

    public static Map<String, String> TECHNIQUES = Map.of(
            "等价类", "等价类", "边界值", "边界值", "异常场景", "异常场景",
            "权限场景", "权限场景", "正向场景", "正向场景");
}
