package io.github.calendula597.testplatform.platformserver.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.api.IdentityDtos;
import io.github.calendula597.testplatform.platformserver.agent.AgentExecutor;
import io.github.calendula597.testplatform.platformserver.agent.AgentOrchestrationService;
import io.github.calendula597.testplatform.platformserver.agent.AgentService;
import io.github.calendula597.testplatform.platformserver.agent.AgentSession;
import io.github.calendula597.testplatform.platformserver.agent.AgentSessionMapper;
import io.github.calendula597.testplatform.platformserver.agent.ClarificationQuestion;
import io.github.calendula597.testplatform.platformserver.agent.ClarificationQuestionMapper;
import io.github.calendula597.testplatform.platformserver.agent.CoveragePoint;
import io.github.calendula597.testplatform.platformserver.agent.CoveragePointMapper;
import io.github.calendula597.testplatform.platformserver.agent.GenerationTask;
import io.github.calendula597.testplatform.platformserver.agent.GenerationTaskMapper;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent session API: start analysis, answer blocking questions, confirm
 * coverage points, cancel, and inspect phase/usage/error state.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent")
public class AgentController {

    private final ProjectAccessService access;
    private final AgentOrchestrationService orchestration;
    private final AgentSessionMapper sessionMapper;
    private final ClarificationQuestionMapper questionMapper;
    private final CoveragePointMapper coverageMapper;
    private final GenerationTaskMapper taskMapper;
    private final AgentExecutor executor;

    public AgentController(ProjectAccessService access, AgentOrchestrationService orchestration,
                           AgentSessionMapper sessionMapper, ClarificationQuestionMapper questionMapper,
                           CoveragePointMapper coverageMapper, GenerationTaskMapper taskMapper,
                           AgentExecutor executor) {
        this.access = access;
        this.orchestration = orchestration;
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.coverageMapper = coverageMapper;
        this.taskMapper = taskMapper;
        this.executor = executor;
    }

    public record StartRequest(Long requirementId) {
    }

    public record AnswerRequest(String answer) {
    }

    public record ConfirmRequest(List<AgentOrchestrationService.CoverageConfirmation> confirmations) {
    }

    @PostMapping("/sessions")
    public ResponseEntity<?> start(@PathVariable Long projectId, @RequestBody StartRequest body,
                                   Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        if (body.requirementId() == null) {
            return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, "需求不能为空"));
        }
        AgentSession session;
        try {
            session = orchestration.startSession(projectId, body.requirementId(), principal);
        } catch (AgentService.QuotaExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new IdentityDtos.ErrorResponse(429, e.getMessage()));
        }
        executor.submit(session.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionView(session));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<?> sessionDetail(@PathVariable Long projectId, @PathVariable Long sessionId,
                                           Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        AgentSession session = requireSession(projectId, sessionId);
        GenerationTask task = orchestration.latestTask(sessionId);
        Map<String, Object> view = sessionView(session);
        view.put("task", task == null ? null : Map.of(
                "status", task.getStatus(), "phase", task.getPhase() == null ? "" : task.getPhase(),
                "attempts", task.getAttempts(), "maxAttempts", task.getMaxAttempts(),
                "promptTokens", task.getPromptTokens(), "completionTokens", task.getCompletionTokens(),
                "error", task.getError() == null ? "" : task.getError()));
        view.put("questions", questionMapper.selectList(new LambdaQueryWrapper<ClarificationQuestion>()
                .eq(ClarificationQuestion::getSessionId, sessionId)
                .orderByAsc(ClarificationQuestion::getOrderIndex)));
        view.put("coveragePoints", coverageMapper.selectList(new LambdaQueryWrapper<CoveragePoint>()
                .eq(CoveragePoint::getSessionId, sessionId)
                .orderByAsc(CoveragePoint::getId)));
        return ResponseEntity.ok(view);
    }

    @PostMapping("/sessions/{sessionId}/questions/{questionId}/answer")
    public ResponseEntity<?> answer(@PathVariable Long projectId, @PathVariable Long sessionId,
                                    @PathVariable Long questionId, @RequestBody AnswerRequest body,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        requireSession(projectId, sessionId);
        AgentSession session = orchestration.answer(sessionId, questionId, body.answer());
        return ResponseEntity.ok(Map.of("status", session.getStatus()));
    }

    @PostMapping("/sessions/{sessionId}/coverage/confirm")
    public ResponseEntity<?> confirm(@PathVariable Long projectId, @PathVariable Long sessionId,
                                     @RequestBody ConfirmRequest body, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        requireSession(projectId, sessionId);
        AgentSession session = orchestration.confirmCoverage(sessionId, body.confirmations());
        return ResponseEntity.ok(Map.of("status", session.getStatus()));
    }

    @PostMapping("/sessions/{sessionId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long projectId, @PathVariable Long sessionId,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        requireSession(projectId, sessionId);
        AgentSession session = orchestration.cancel(sessionId);
        return ResponseEntity.ok(Map.of("status", session.getStatus()));
    }

    private Map<String, Object> sessionView(AgentSession session) {
        return new java.util.HashMap<>(Map.of(
                "id", session.getId(),
                "requirementId", session.getRequirementId(),
                "status", session.getStatus(),
                "model", session.getModel() == null ? "" : session.getModel(),
                "promptTokens", session.getPromptTokens(),
                "completionTokens", session.getCompletionTokens(),
                "error", session.getErrorMessage() == null ? "" : session.getErrorMessage()));
    }

    private AgentSession requireSession(Long projectId, Long sessionId) {
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("会话不存在");
        }
        return session;
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
}
