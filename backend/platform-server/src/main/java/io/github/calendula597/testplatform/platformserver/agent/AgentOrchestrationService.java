package io.github.calendula597.testplatform.platformserver.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Persists agent sessions, tasks and their outputs, and runs the async
 * analysis workflow with checkpoint cancellation and bounded retries.
 */
@Service
public class AgentOrchestrationService {

    private final AgentService agentService;
    private final AgentSessionMapper sessionMapper;
    private final ClarificationQuestionMapper questionMapper;
    private final CoveragePointMapper coverageMapper;
    private final GenerationTaskMapper taskMapper;
    private final ModelClient modelClient;
    private final AuditService auditService;

    public AgentOrchestrationService(AgentService agentService, AgentSessionMapper sessionMapper,
                                     ClarificationQuestionMapper questionMapper,
                                     CoveragePointMapper coverageMapper, GenerationTaskMapper taskMapper,
                                     ModelClient modelClient, AuditService auditService) {
        this.agentService = agentService;
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.coverageMapper = coverageMapper;
        this.taskMapper = taskMapper;
        this.modelClient = modelClient;
        this.auditService = auditService;
    }

    @Transactional
    public AgentSession startSession(Long projectId, Long requirementId, UserPrincipal principal) {
        long active = sessionMapper.selectCount(new LambdaQueryWrapper<AgentSession>()
                .eq(AgentSession::getProjectId, projectId)
                .in(AgentSession::getStatus, AgentSession.STATUS_ANALYZING,
                        AgentSession.STATUS_WAITING_CLARIFICATION,
                        AgentSession.STATUS_COVERAGE_CONFIRMATION));
        agentService.assertQuota(active, projectId);

        AgentSession session = new AgentSession();
        session.setProjectId(projectId);
        session.setRequirementId(requirementId);
        session.setStatus(AgentSession.STATUS_ANALYZING);
        session.setModel(modelName());
        session.setPromptTokens(0);
        session.setCompletionTokens(0);
        session.setCreatedBy(principal.id());
        sessionMapper.insert(session);

        GenerationTask task = new GenerationTask();
        task.setSessionId(session.getId());
        task.setStatus(GenerationTask.STATUS_QUEUED);
        task.setPhase("RETRIEVING");
        task.setAttempts(0);
        task.setMaxAttempts(3);
        task.setPromptTokens(0);
        task.setCompletionTokens(0);
        taskMapper.insert(task);

        auditService.record(principalAuthentication(principal), "AGENT_SESSION_STARTED", null,
                Map.of("projectId", projectId, "requirementId", requirementId, "sessionId", session.getId()));
        return session;
    }

    public GenerationTask latestTask(Long sessionId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<GenerationTask>()
                .eq(GenerationTask::getSessionId, sessionId)
                .orderByDesc(GenerationTask::getId)
                .last("LIMIT 1"));
    }

    /**
     * Async entry point: resolves the latest task for the session and runs
     * the workflow without a caller transaction.
     */
    public void runAnalysisAsync(Long sessionId) {
        GenerationTask task = latestTask(sessionId);
        if (task != null) {
            runAnalysis(sessionId, task.getId());
        }
    }

    /**
     * Synchronous workflow body, invoked through the async executor. Every
     * phase checks the cancel flag; model failures retry up to the bound.
     */
    @Transactional
    public void runAnalysis(Long sessionId, Long taskId) {
        GenerationTask task = taskMapper.selectById(taskId);
        AgentSession session = sessionMapper.selectById(sessionId);
        if (task == null || session == null
                || GenerationTask.STATUS_CANCELLED.equals(session.getStatus())) {
            return;
        }

        int attempts = 0;
        Exception lastError = null;
        while (attempts < task.getMaxAttempts()) {
            if (isCancelled(sessionId)) {
                return;
            }
            attempts++;
            task.setAttempts(attempts);
            task.setStatus(GenerationTask.STATUS_RUNNING);
            try {
                // Phase 1: authorized context.
                task.setPhase("RETRIEVING");
                taskMapper.updateById(task);
                AgentService.RequirementContext requirement =
                        agentService.loadRequirement(session.getProjectId(), session.getRequirementId());
                AgentService.ScopedKnowledge knowledge = agentService.retrieveKnowledge(
                        requirement.title(), session.getProjectId());

                // Phase 2: model analysis (bounded retries inside the loop).
                if (isCancelled(sessionId)) {
                    return;
                }
                task.setPhase("THINKING");
                taskMapper.updateById(task);
                ModelClient.Completion completion =
                        agentService.callModel(agentService.buildAnalysisPrompt(requirement, knowledge));

                // Phase 3: parse and persist outputs.
                if (isCancelled(sessionId)) {
                    return;
                }
                task.setPhase("PERSISTING");
                AgentService.AnalysisOutput output = agentService.parseAnalysis(completion.content());
                persistOutputs(session, output);

                session.setStatus(output.clarifications().stream().anyMatch(AgentService.ClarifiedQuestion::blocking)
                        ? AgentSession.STATUS_WAITING_CLARIFICATION
                        : AgentSession.STATUS_COVERAGE_CONFIRMATION);
                session.setPromptTokens(session.getPromptTokens() + completion.promptTokens());
                session.setCompletionTokens(session.getCompletionTokens() + completion.completionTokens());
                sessionMapper.updateById(session);
                task.setStatus(GenerationTask.STATUS_DONE);
                task.setPromptTokens(completion.promptTokens());
                task.setCompletionTokens(completion.completionTokens());
                taskMapper.updateById(task);
                return;
            } catch (Exception e) {
                lastError = e;
                task.setError(e.getMessage());
                taskMapper.updateById(task);
            }
        }
        session.setStatus(AgentSession.STATUS_FAILED);
        session.setErrorMessage(lastError == null ? "未知错误" : lastError.getMessage());
        sessionMapper.updateById(session);
        task.setStatus(GenerationTask.STATUS_FAILED);
        taskMapper.updateById(task);
    }

    private void persistOutputs(AgentSession session, AgentService.AnalysisOutput output) {
        int order = 1;
        for (AgentService.ClarifiedQuestion q : output.clarifications()) {
            ClarificationQuestion row = new ClarificationQuestion();
            row.setSessionId(session.getId());
            row.setOrderIndex(order++);
            row.setQuestion(q.question());
            row.setBlocking(q.blocking());
            row.setAnswered(false);
            questionMapper.insert(row);
        }
        for (AgentService.ProposedPoint p : output.coveragePoints()) {
            CoveragePoint row = new CoveragePoint();
            row.setSessionId(session.getId());
            row.setDescription(p.description());
            row.setTechnique(p.technique());
            row.setIsAssumption(false);
            row.setConfirmed(false);
            coverageMapper.insert(row);
        }
    }

    private boolean isCancelled(Long sessionId) {
        AgentSession current = sessionMapper.selectById(sessionId);
        return current != null && AgentSession.STATUS_CANCELLED.equals(current.getStatus());
    }

    @Transactional
    public AgentSession answer(Long sessionId, Long questionId, String answer) {
        ClarificationQuestion question = questionMapper.selectById(questionId);
        if (question == null || !question.getSessionId().equals(sessionId)) {
            throw new ResourceNotFoundException("澄清问题不存在");
        }
        question.setAnswer(answer);
        question.setAnswered(answer != null && !answer.isBlank());
        questionMapper.updateById(question);

        AgentSession session = sessionMapper.selectById(sessionId);
        List<ClarificationQuestion> all = questionMapper.selectList(
                new LambdaQueryWrapper<ClarificationQuestion>()
                        .eq(ClarificationQuestion::getSessionId, sessionId));
        boolean anyBlockingOpen = all.stream()
                .filter(q -> Boolean.TRUE.equals(q.getBlocking()))
                .anyMatch(q -> !Boolean.TRUE.equals(q.getAnswered()));
        if (!anyBlockingOpen && AgentSession.STATUS_WAITING_CLARIFICATION.equals(session.getStatus())) {
            session.setStatus(AgentSession.STATUS_COVERAGE_CONFIRMATION);
            sessionMapper.updateById(session);
        }
        return session;
    }

    @Transactional
    public AgentSession confirmCoverage(Long sessionId, List<CoverageConfirmation> confirmations) {
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ResourceNotFoundException("会话不存在");
        }
        for (CoverageConfirmation confirmation : confirmations) {
            CoveragePoint point = coverageMapper.selectById(confirmation.pointId());
            if (point == null || !point.getSessionId().equals(sessionId)) {
                continue;
            }
            point.setConfirmed(confirmation.confirmed());
            point.setUserNote(confirmation.userNote());
            coverageMapper.updateById(point);
        }
        if (AgentSession.STATUS_COVERAGE_CONFIRMATION.equals(session.getStatus())) {
            session.setStatus(AgentSession.STATUS_READY_FOR_DRAFT);
            sessionMapper.updateById(session);
        }
        return session;
    }

    @Transactional
    public AgentSession cancel(Long sessionId) {
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ResourceNotFoundException("会话不存在");
        }
        session.setStatus(AgentSession.STATUS_CANCELLED);
        sessionMapper.updateById(session);
        GenerationTask task = latestTask(sessionId);
        if (task != null && (GenerationTask.STATUS_QUEUED.equals(task.getStatus())
                || GenerationTask.STATUS_RUNNING.equals(task.getStatus()))) {
            task.setStatus(GenerationTask.STATUS_CANCELLED);
            taskMapper.updateById(task);
        }
        return session;
    }

    public record CoverageConfirmation(Long pointId, boolean confirmed, String userNote) {
    }

    private org.springframework.security.core.Authentication principalAuthentication(UserPrincipal principal) {
        return org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(principal, null, principal.getAuthorities());
    }

    private String modelName() {
        return modelClient.isConfigured() ? modelClient.model() : null;
    }
}
