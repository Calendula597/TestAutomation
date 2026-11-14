package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.agent.AgentSession;
import io.github.calendula597.testplatform.platformserver.agent.AgentSessionMapper;
import io.github.calendula597.testplatform.platformserver.agent.CoveragePoint;
import io.github.calendula597.testplatform.platformserver.agent.CoveragePointMapper;
import io.github.calendula597.testplatform.platformserver.agent.DraftBatch;
import io.github.calendula597.testplatform.platformserver.agent.DraftBatchMapper;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.manual.ManualRunItem;
import io.github.calendula597.testplatform.platformserver.manual.ManualRunItemMapper;
import io.github.calendula597.testplatform.platformserver.manual.TestPlanItem;
import io.github.calendula597.testplatform.platformserver.manual.TestPlanItemMapper;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.requirement.Requirement;
import io.github.calendula597.testplatform.platformserver.requirement.RequirementMapper;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersion;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersionMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Requirement coverage matrix: requirement -> confirmed coverage points ->
 * READY case versions -> latest manual execution status. The status is
 * derived from real traceability, not from counting cases.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/coverage")
public class CoverageController {

    private final ProjectAccessService access;
    private final RequirementMapper requirementMapper;
    private final AgentSessionMapper sessionMapper;
    private final CoveragePointMapper coveragePointMapper;
    private final DraftBatchMapper draftBatchMapper;
    private final TestCaseVersionMapper versionMapper;
    private final TestPlanItemMapper planItemMapper;
    private final ManualRunItemMapper runItemMapper;

    public CoverageController(ProjectAccessService access, RequirementMapper requirementMapper,
                              AgentSessionMapper sessionMapper, CoveragePointMapper coveragePointMapper,
                              DraftBatchMapper draftBatchMapper, TestCaseVersionMapper versionMapper,
                              TestPlanItemMapper planItemMapper, ManualRunItemMapper runItemMapper) {
        this.access = access;
        this.requirementMapper = requirementMapper;
        this.sessionMapper = sessionMapper;
        this.coveragePointMapper = coveragePointMapper;
        this.draftBatchMapper = draftBatchMapper;
        this.versionMapper = versionMapper;
        this.planItemMapper = planItemMapper;
        this.runItemMapper = runItemMapper;
    }

    public record CaseVersionTrace(Long versionId, String title, int version, String latestRunStatus,
                                   String lastExecutedAt) {
    }

    public record CoverageRow(Long requirementId, String title, int currentVersion, String status,
                              long confirmedCoveragePoints, long pendingDrafts, List<CaseVersionTrace> caseVersions) {
    }

    @GetMapping
    public List<CoverageRow> matrix(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);

        List<Requirement> requirements = requirementMapper.selectList(
                new LambdaQueryWrapper<Requirement>()
                        .eq(Requirement::getProjectId, projectId)
                        .orderByDesc(Requirement::getId));

        List<CoverageRow> rows = new ArrayList<>();
        for (Requirement requirement : requirements) {
            long confirmedPoints = coveragePointMapper.selectCount(
                    new LambdaQueryWrapper<CoveragePoint>()
                            .eq(CoveragePoint::getConfirmed, true)
                            .inSql(CoveragePoint::getSessionId,
                                    "SELECT id FROM agent_session WHERE requirement_id = "
                                            + requirement.getId()));

            long pendingDrafts = draftBatchMapper.selectCount(new LambdaQueryWrapper<DraftBatch>()
                    .eq(DraftBatch::getRequirementId, requirement.getId())
                    .eq(DraftBatch::getStatus, DraftBatch.STATUS_PENDING_REVIEW));

            AgentSession latestSession = sessionMapper.selectOne(new LambdaQueryWrapper<AgentSession>()
                    .eq(AgentSession::getRequirementId, requirement.getId())
                    .orderByDesc(AgentSession::getId)
                    .last("LIMIT 1"));

            List<TestCaseVersion> versions = versionMapper.selectList(
                    new LambdaQueryWrapper<TestCaseVersion>()
                            .eq(TestCaseVersion::getRequirementId, requirement.getId())
                            .orderByDesc(TestCaseVersion::getVersion));
            List<CaseVersionTrace> traces = versions.stream().map(v -> {
                TestPlanItem planItem = planItemMapper.selectOne(new LambdaQueryWrapper<TestPlanItem>()
                        .eq(TestPlanItem::getCaseVersionId, v.getId())
                        .last("LIMIT 1"));
                ManualRunItem runItem = planItem == null ? null : runItemMapper.selectOne(
                        new LambdaQueryWrapper<ManualRunItem>()
                                .eq(ManualRunItem::getPlanItemId, planItem.getId())
                                .orderByDesc(ManualRunItem::getUpdatedAt)
                                .last("LIMIT 1"));
                return new CaseVersionTrace(v.getId(), v.getTitle(), v.getVersion(),
                        runItem == null ? "NOT_EXECUTED" : runItem.getStatus(),
                        runItem == null ? null : runItem.getUpdatedAt().toString());
            }).toList();

            rows.add(new CoverageRow(requirement.getId(), requirement.getTitle(),
                    requirement.getCurrentVersion(), statusOf(latestSession, pendingDrafts,
                    confirmedPoints, traces), confirmedPoints, pendingDrafts, traces));
        }
        return rows;
    }

    /** Explicit status ladder; a failed execution dominates "covered". */
    static String statusOf(AgentSession latestSession, long pendingDrafts, long confirmedPoints,
                           List<CaseVersionTrace> traces) {
        boolean anyFailed = traces.stream().anyMatch(t -> "FAILED".equals(t.latestRunStatus()));
        if (anyFailed) {
            return "EXEC_FAILED";
        }
        boolean allPassed = !traces.isEmpty()
                && traces.stream().allMatch(t -> "PASSED".equals(t.latestRunStatus()));
        if (allPassed) {
            return "EXEC_PASSED";
        }
        if (!traces.isEmpty()) {
            return "COVERED";
        }
        if (pendingDrafts > 0) {
            return "PENDING_REVIEW";
        }
        if (latestSession != null && (AgentSession.STATUS_ANALYZING.equals(latestSession.getStatus())
                || AgentSession.STATUS_WAITING_CLARIFICATION.equals(latestSession.getStatus())
                || AgentSession.STATUS_COVERAGE_CONFIRMATION.equals(latestSession.getStatus()))) {
            return "GENERATING";
        }
        if (confirmedPoints > 0) {
            return "COVERED_POINTS_ONLY";
        }
        return "NOT_COVERED";
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<?> forbidden(InsufficientRoleException e) {
        return ResponseEntity.status(403).body(new IdentityDtos.ErrorResponse(403, e.getMessage()));
    }
}
