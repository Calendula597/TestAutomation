package io.github.calendula597.testplatform.platformserver.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeChunk;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeChunkMapper;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeVersion;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeVersionMapper;
import io.github.calendula597.testplatform.platformserver.testcase.TestCase;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseMapper;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseStep;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseStepMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The agent's whitelist tools. Anything outside this registry is refused
 * with a visible observation — the model cannot publish, execute, delete,
 * read secrets or reach target systems.
 */
@Component
public class AgentTools {

    public static final String SEARCH_KNOWLEDGE = "search_knowledge";
    public static final String GET_REQUIREMENT = "get_requirement";
    public static final String FIND_DUPLICATES = "find_duplicates";
    public static final String LIST_RETRIEVED = "list_retrieved";

    private static final Set<String> WHITELIST = Set.of(
            SEARCH_KNOWLEDGE, GET_REQUIREMENT, FIND_DUPLICATES, LIST_RETRIEVED);

    private final AgentService agentService;
    private final TestCaseMapper testCaseMapper;
    private final TestCaseStepMapper stepMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeVersionMapper knowledgeVersionMapper;
    private final JdbcTemplate jdbcTemplate;

    public AgentTools(AgentService agentService, TestCaseMapper testCaseMapper,
                      TestCaseStepMapper stepMapper, KnowledgeChunkMapper chunkMapper,
                      KnowledgeVersionMapper knowledgeVersionMapper, JdbcTemplate jdbcTemplate) {
        this.agentService = agentService;
        this.testCaseMapper = testCaseMapper;
        this.stepMapper = stepMapper;
        this.chunkMapper = chunkMapper;
        this.knowledgeVersionMapper = knowledgeVersionMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isWhitelisted(String tool) {
        return WHITELIST.contains(tool);
    }

    public static String whitelistDescription() {
        return """
                可用工具（仅限这些）：
                - search_knowledge: 按关键词检索授权知识，返回文档与分块（含引用所需的 documentId/version）
                - get_requirement: 读取当前需求全文与验收标准
                - find_duplicates: 按标题关键词查找项目内已有用例（用于重复/更新建议）
                - list_retrieved: 列出本次会话已检索到的知识文档（id/标题/版本），用于引用
                """;
    }

    public record ToolObservation(String output, List<CitedKnowledge> retrievedKnowledge) {
    }

    public record CitedKnowledge(long documentId, String title, int version, long versionId) {
    }

    public ToolObservation execute(String tool, String input, Long projectId,
                                   AgentService.RequirementContext requirement,
                                   Set<CitedKnowledge> retrievalPool) {
        return switch (tool) {
            case SEARCH_KNOWLEDGE -> {
                AgentService.ScopedKnowledge knowledge = agentService.retrieveKnowledge(input, projectId);
                List<CitedKnowledge> cited = knowledgeChunksWithIds(input, projectId);
                cited.forEach(retrievalPool::add);
                yield new ToolObservation(
                        knowledge.chunks().isEmpty()
                                ? "未检索到相关知识（知识不足时请在 final 中标记 knowledgeInsufficient）"
                                : String.join("\n---\n", knowledge.chunks()),
                        cited);
            }
            case GET_REQUIREMENT -> new ToolObservation(
                    "标题：" + requirement.title() + "\n正文：\n" + requirement.body()
                            + "\n验收标准：\n" + requirement.acceptanceCriteria(),
                    List.of());
            case FIND_DUPLICATES -> new ToolObservation(findDuplicates(input, projectId), List.of());
            case LIST_RETRIEVED -> new ToolObservation(
                    retrievalPool.stream()
                            .map(k -> "documentId=" + k.documentId() + " 版本=" + k.version() + " 标题=" + k.title())
                            .collect(Collectors.joining("\n")),
                    List.of());
            default -> new ToolObservation("未知工具被拒绝。仅可用：" + WHITELIST, List.of());
        };
    }

    private String findDuplicates(String titleLike, Long projectId) {
        List<TestCase> matches = testCaseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getProjectId, projectId)
                .apply("title ILIKE {0}", "%" + titleLike.replace("%", "\\%") + "%")
                .last("LIMIT 10"));
        if (matches.isEmpty()) {
            return "无相似标题的已有用例。";
        }
        StringBuilder sb = new StringBuilder();
        for (TestCase testCase : matches) {
            List<TestCaseStep> steps = stepMapper.selectList(new LambdaQueryWrapper<TestCaseStep>()
                    .eq(TestCaseStep::getCaseId, testCase.getId())
                    .orderByAsc(TestCaseStep::getStepOrder));
            sb.append("caseId=").append(testCase.getId())
                    .append(" 标题=").append(testCase.getTitle())
                    .append(" 步骤数=").append(steps.size())
                    .append(" 步骤=").append(steps.stream()
                            .map(s -> s.getAction())
                            .collect(Collectors.joining(" / ")))
                    .append("\n");
        }
        return sb.toString();
    }

    private List<CitedKnowledge> knowledgeChunksWithIds(String query, Long projectId) {
        String like = "%" + query.replace("%", "\\%") + "%";
        List<KnowledgeChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                .apply("content ILIKE {0}", like)
                .apply("version_id IN (SELECT kv.id FROM knowledge_version kv "
                        + "JOIN knowledge_document kd ON kd.id = kv.document_id "
                        + "WHERE kd.scope = 'GLOBAL' OR kd.project_id = " + projectId + ")")
                .last("LIMIT " + AgentService.MAX_KNOWLEDGE_CHUNKS));
        List<CitedKnowledge> result = new ArrayList<>();
        Set<Long> seenVersions = new HashSet<>();
        for (KnowledgeChunk chunk : chunks) {
            KnowledgeVersion version = knowledgeVersionMapper.selectById(chunk.getVersionId());
            if (version == null || !seenVersions.add(version.getDocumentId())) {
                continue;
            }
            result.add(new CitedKnowledge(version.getDocumentId(), documentTitle(version.getDocumentId()),
                    version.getVersion(), version.getId()));
        }
        return result;
    }

    private String documentTitle(Long documentId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT title FROM knowledge_document WHERE id = ?", documentId);
        return String.valueOf(row.get("title"));
    }
}
