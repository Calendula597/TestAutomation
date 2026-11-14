package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.agent.AgentSession;
import io.github.calendula597.testplatform.platformserver.agent.AgentSessionMapper;
import io.github.calendula597.testplatform.platformserver.agent.AgentToolCall;
import io.github.calendula597.testplatform.platformserver.agent.AgentToolCallMapper;
import io.github.calendula597.testplatform.platformserver.agent.DraftBatch;
import io.github.calendula597.testplatform.platformserver.agent.DraftBatchCase;
import io.github.calendula597.testplatform.platformserver.agent.DraftBatchCaseMapper;
import io.github.calendula597.testplatform.platformserver.agent.DraftBatchMapper;
import io.github.calendula597.testplatform.platformserver.agent.DraftGenerationService;
import io.github.calendula597.testplatform.platformserver.agent.GenerationTask;
import io.github.calendula597.testplatform.platformserver.agent.GenerationTaskMapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.testcase.TestCase;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseMapper;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseStep;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseStepMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Draft batch lifecycle: generation from a confirmed session, provenance
 * view (model, prompt version, tool calls, citations) and acceptance into
 * the case grid as ordinary drafts.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent")
public class DraftController {

    private final ProjectAccessService access;
    private final AgentSessionMapper sessionMapper;
    private final DraftBatchMapper batchMapper;
    private final DraftBatchCaseMapper batchCaseMapper;
    private final AgentToolCallMapper toolCallMapper;
    private final GenerationTaskMapper taskMapper;
    private final DraftGenerationService generationService;
    private final TestCaseMapper testCaseMapper;
    private final TestCaseStepMapper stepMapper;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public DraftController(ProjectAccessService access, AgentSessionMapper sessionMapper,
                           DraftBatchMapper batchMapper, DraftBatchCaseMapper batchCaseMapper,
                           AgentToolCallMapper toolCallMapper, GenerationTaskMapper taskMapper,
                           DraftGenerationService generationService, TestCaseMapper testCaseMapper,
                           TestCaseStepMapper stepMapper, ObjectMapper objectMapper,
                           AuditService auditService) {
        this.access = access;
        this.sessionMapper = sessionMapper;
        this.batchMapper = batchMapper;
        this.batchCaseMapper = batchCaseMapper;
        this.toolCallMapper = toolCallMapper;
        this.taskMapper = taskMapper;
        this.generationService = generationService;
        this.testCaseMapper = testCaseMapper;
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @PostMapping("/sessions/{sessionId}/generate-draft")
    public ResponseEntity<?> generate(@PathVariable Long projectId, @PathVariable Long sessionId,
                                      Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        AgentSession session = requireSession(projectId, sessionId);
        if (!AgentSession.STATUS_READY_FOR_DRAFT.equals(session.getStatus())) {
            return ResponseEntity.badRequest()
                    .body(new IdentityDtos.ErrorResponse(400, "会话未完成覆盖点确认，不能生成草稿"));
        }
        // Idempotency: a session already owns a batch — retries reuse it and
        // never create a second batch.
        DraftBatch existing = latestBatch(sessionId);
        if (existing != null && !DraftBatch.STATUS_FAILED.equals(existing.getStatus())) {
            return ResponseEntity.ok(batchView(existing));
        }
        DraftBatch batch = generationService.generate(session);
        auditService.record(authentication, "AGENT_DRAFT_GENERATED", null,
                Map.of("projectId", projectId, "sessionId", sessionId,
                        "batchId", batch.getId(), "status", batch.getStatus()));
        return ResponseEntity.status(HttpStatus.CREATED).body(batchView(batch));
    }

    @GetMapping("/sessions/{sessionId}/draft")
    public ResponseEntity<?> draft(@PathVariable Long projectId, @PathVariable Long sessionId,
                                   Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        requireSession(projectId, sessionId);
        DraftBatch batch = latestBatch(sessionId);
        if (batch == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(batchView(batch));
    }

    @PostMapping("/sessions/{sessionId}/draft/accept")
    @Transactional
    public ResponseEntity<?> accept(@PathVariable Long projectId, @PathVariable Long sessionId,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        requireSession(projectId, sessionId);
        DraftBatch batch = latestBatch(sessionId);
        if (batch == null) {
            return ResponseEntity.notFound().build();
        }
        if (!DraftBatch.STATUS_PENDING_REVIEW.equals(batch.getStatus())) {
            return badRequest("批次不是待审核状态");
        }

        List<DraftBatchCase> cases = batchCaseMapper.selectList(
                new LambdaQueryWrapper<DraftBatchCase>()
                        .eq(DraftBatchCase::getBatchId, batch.getId())
                        .orderByAsc(DraftBatchCase::getId));
        List<Long> createdCaseIds = new java.util.ArrayList<>();
        for (DraftBatchCase draftCase : cases) {
            TestCase testCase = new TestCase();
            testCase.setProjectId(projectId);
            testCase.setTitle(draftCase.getTitle());
            testCase.setPriority(draftCase.getPriority() == null ? TestCase.PRIORITY_MEDIUM
                    : draftCase.getPriority());
            testCase.setStatus(TestCase.STATUS_DRAFT);
            testCase.setVersion(1);
            testCase.setCreatedBy(currentUserId(principal));
            testCaseMapper.insert(testCase);
            createdCaseIds.add(testCase.getId());

            List<Map<String, Object>> steps = readSteps(draftCase.getStepsJson());
            int order = 1;
            for (Map<String, Object> step : steps) {
                TestCaseStep row = new TestCaseStep();
                row.setCaseId(testCase.getId());
                row.setStepOrder(order++);
                row.setAction(String.valueOf(step.get("action")));
                row.setTestData(step.get("testData") == null ? "" : String.valueOf(step.get("testData")));
                row.setExpected(String.valueOf(step.get("expected")));
                stepMapper.insert(row);
            }
        }

        batch.setStatus(DraftBatch.STATUS_ACCEPTED);
        batchMapper.updateById(batch);
        auditService.record(authentication, "AGENT_DRAFT_ACCEPTED", null,
                Map.of("projectId", projectId, "batchId", batch.getId(),
                        "cases", createdCaseIds.size()));
        return ResponseEntity.ok(Map.of("status", batch.getStatus(), "createdCaseIds", createdCaseIds));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readSteps(String stepsJson) {
        try {
            return objectMapper.readValue(stepsJson, List.class);
        } catch (Exception e) {
            throw new IllegalStateException("草稿步骤反序列化失败", e);
        }
    }

    private Map<String, Object> batchView(DraftBatch batch) {
        Map<String, Object> view = new java.util.HashMap<>(Map.of(
                "id", batch.getId(),
                "sessionId", batch.getSessionId(),
                "status", batch.getStatus(),
                "model", batch.getModel() == null ? "" : batch.getModel(),
                "promptVersion", batch.getPromptVersion(),
                "promptTokens", batch.getPromptTokens(),
                "completionTokens", batch.getCompletionTokens(),
                "error", batch.getError() == null ? "" : batch.getError()));
        List<Map<String, Object>> cases = new java.util.ArrayList<>();
        for (DraftBatchCase draftCase : batchCaseMapper.selectList(new LambdaQueryWrapper<DraftBatchCase>()
                .eq(DraftBatchCase::getBatchId, batch.getId())
                .orderByAsc(DraftBatchCase::getId))) {
            Map<String, Object> caseView = new java.util.HashMap<>();
            caseView.put("id", draftCase.getId());
            caseView.put("title", draftCase.getTitle());
            caseView.put("priority", draftCase.getPriority() == null ? "" : draftCase.getPriority());
            caseView.put("technique", draftCase.getTechnique() == null ? "" : draftCase.getTechnique());
            caseView.put("suggestion", draftCase.getSuggestion());
            caseView.put("duplicateOfCaseId", draftCase.getDuplicateOfCaseId());
            caseView.put("knowledgeInsufficient", draftCase.getKnowledgeInsufficient());
            caseView.put("steps", readSteps(draftCase.getStepsJson()));
            caseView.put("citations", readList(draftCase.getCitationsJson()));
            cases.add(caseView);
        }
        view.put("cases", cases);
        view.put("toolCalls", toolCallMapper.selectList(new LambdaQueryWrapper<AgentToolCall>()
                .eq(AgentToolCall::getBatchId, batch.getId())
                .orderByAsc(AgentToolCall::getId)));
        view.put("requirementId", batch.getRequirementId());
        return view;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readList(String json) {
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private DraftBatch latestBatch(Long sessionId) {
        return batchMapper.selectOne(new LambdaQueryWrapper<DraftBatch>()
                .eq(DraftBatch::getSessionId, sessionId)
                .orderByDesc(DraftBatch::getId)
                .last("LIMIT 1"));
    }

    private AgentSession requireSession(Long projectId, Long sessionId) {
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("会话不存在");
        }
        return session;
    }

    private Long currentUserId(UserPrincipal principal) {
        return principal.id();
    }

    @ExceptionHandler({ResourceNotFoundException.class, InsufficientRoleException.class})
    public ResponseEntity<?> mapped(RuntimeException e) {
        if (e instanceof ResourceNotFoundException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new IdentityDtos.ErrorResponse(404, e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new IdentityDtos.ErrorResponse(403, e.getMessage()));
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, message));
    }
}
