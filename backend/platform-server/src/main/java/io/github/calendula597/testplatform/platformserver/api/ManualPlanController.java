package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.manual.ManualRun;
import io.github.calendula597.testplatform.platformserver.manual.ManualRunItem;
import io.github.calendula597.testplatform.platformserver.manual.ManualRunItemMapper;
import io.github.calendula597.testplatform.platformserver.manual.ManualRunMapper;
import io.github.calendula597.testplatform.platformserver.manual.TestPlan;
import io.github.calendula597.testplatform.platformserver.manual.TestPlanItem;
import io.github.calendula597.testplatform.platformserver.manual.TestPlanItemMapper;
import io.github.calendula597.testplatform.platformserver.manual.TestPlanMapper;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.testcase.TestCase;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseMapper;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersion;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersionMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manual test plans over immutable READY case versions, resumable runs and
 * aggregated reports. Executing references published snapshots only.
 */
@RestController
public class ManualPlanController {

    private static final Set<String> ITEM_STATUSES = Set.of(
            ManualRunItem.STATUS_PASSED, ManualRunItem.STATUS_FAILED,
            ManualRunItem.STATUS_BLOCKED, ManualRunItem.STATUS_SKIPPED,
            ManualRunItem.STATUS_PENDING);

    private final ProjectAccessService access;
    private final TestPlanMapper planMapper;
    private final TestPlanItemMapper planItemMapper;
    private final ManualRunMapper runMapper;
    private final ManualRunItemMapper runItemMapper;
    private final TestCaseVersionMapper versionMapper;
    private final TestCaseMapper testCaseMapper;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public ManualPlanController(ProjectAccessService access, TestPlanMapper planMapper,
                                TestPlanItemMapper planItemMapper, ManualRunMapper runMapper,
                                ManualRunItemMapper runItemMapper, TestCaseVersionMapper versionMapper,
                                TestCaseMapper testCaseMapper, ObjectMapper objectMapper,
                                AuditService auditService) {
        this.access = access;
        this.planMapper = planMapper;
        this.planItemMapper = planItemMapper;
        this.runMapper = runMapper;
        this.runItemMapper = runItemMapper;
        this.versionMapper = versionMapper;
        this.testCaseMapper = testCaseMapper;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public record PlanCreateRequest(String name, List<Long> caseVersionIds) {
    }

    public record PlanItemView(Long id, int orderIndex, Long caseVersionId, String caseTitle,
                               int caseVersion, String caseStatus) {
    }

    public record PlanView(Long id, String name, String status, List<PlanItemView> items) {
    }

    public record RecordRequest(String status, String actualResult, String note, String evidenceUrl) {
    }

    public record RunItemView(Long id, Long planItemId, int orderIndex, String status,
                              String actualResult, String note, String evidenceUrl,
                              String caseTitle, int caseVersion, List<Map<String, Object>> steps) {
    }

    public record RunView(Long id, Long planId, String planName, String status, String executedBy,
                          String startedAt, String completedAt, List<RunItemView> items) {
    }

    public record ReportSummary(long total, long passed, long failed, long blocked, long skipped,
                                long pending, String executedBy, String startedAt, String completedAt,
                                String runStatus) {
    }

    public record PublishedVersionView(Long versionId, Long caseId, String caseTitle, int version,
                                       String priority) {
    }

    /** Latest READY snapshot per case — the pick list for plan creation. */
    @GetMapping("/api/v1/projects/{projectId}/testcases/published")
    public List<PublishedVersionView> publishedVersions(@PathVariable Long projectId,
                                                        Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        List<TestCase> cases = testCaseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getProjectId, projectId)
                .orderByAsc(TestCase::getId));
        List<PublishedVersionView> result = new ArrayList<>();
        for (TestCase testCase : cases) {
            TestCaseVersion ready = versionMapper.selectOne(new LambdaQueryWrapper<TestCaseVersion>()
                    .eq(TestCaseVersion::getCaseId, testCase.getId())
                    .orderByDesc(TestCaseVersion::getVersion)
                    .last("LIMIT 1"));
            if (ready != null) {
                result.add(new PublishedVersionView(ready.getId(), testCase.getId(),
                        ready.getTitle(), ready.getVersion(), ready.getPriority()));
            }
        }
        return result;
    }

    @PostMapping("/api/v1/projects/{projectId}/plans")
    @Transactional
    public ResponseEntity<?> createPlan(@PathVariable Long projectId,
                                        @RequestBody PlanCreateRequest body, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        if (body.name() == null || body.name().isBlank() || body.name().length() > 100) {
            return badRequest("计划名称不能为空且不超过 100 字");
        }
        if (body.caseVersionIds() == null || body.caseVersionIds().isEmpty()) {
            return badRequest("至少选择一个用例版本");
        }

        TestPlan plan = new TestPlan();
        plan.setProjectId(projectId);
        plan.setName(body.name().trim());
        plan.setStatus(TestPlan.STATUS_ACTIVE);
        plan.setCreatedBy(principal.id());
        planMapper.insert(plan);

        int order = 1;
        for (Long versionId : body.caseVersionIds()) {
            TestCaseVersion snapshot = versionMapper.selectById(versionId);
            if (snapshot == null || !belongsToProject(snapshot, projectId)) {
                throw new ResourceNotFoundException("用例版本 " + versionId + " 不存在或不属于本项目");
            }
            if (!TestCaseVersion.STATUS_READY.equals(snapshot.getStatus())) {
                return badRequest("只有 READY 版本可进入测试计划：" + snapshot.getTitle());
            }
            TestPlanItem item = new TestPlanItem();
            item.setPlanId(plan.getId());
            item.setOrderIndex(order++);
            item.setCaseVersionId(versionId);
            planItemMapper.insert(item);
        }

        auditService.record(authentication, "PLAN_CREATED", null,
                Map.of("projectId", projectId, "planId", plan.getId(), "items", body.caseVersionIds().size()));
        return ResponseEntity.status(HttpStatus.CREATED).body(planView(plan));
    }

    @GetMapping("/api/v1/projects/{projectId}/plans")
    public List<Map<String, Object>> listPlans(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        return planMapper.selectList(new LambdaQueryWrapper<TestPlan>()
                        .eq(TestPlan::getProjectId, projectId)
                        .orderByDesc(TestPlan::getId))
                .stream()
                .map(p -> Map.<String, Object>of("id", p.getId(), "name", p.getName(), "status", p.getStatus()))
                .toList();
    }

    @GetMapping("/api/v1/projects/{projectId}/plans/{planId}")
    public ResponseEntity<?> planDetail(@PathVariable Long projectId, @PathVariable Long planId,
                                        Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        TestPlan plan = planMapper.selectById(planId);
        if (plan == null || !plan.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("计划不存在");
        }
        return ResponseEntity.ok(planView(plan));
    }

    @PostMapping("/api/v1/projects/{projectId}/plans/{planId}/runs")
    @Transactional
    public ResponseEntity<?> startRun(@PathVariable Long projectId, @PathVariable Long planId,
                                      Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        TestPlan plan = planMapper.selectById(planId);
        if (plan == null || !plan.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("计划不存在");
        }
        List<TestPlanItem> items = planItemMapper.selectList(new LambdaQueryWrapper<TestPlanItem>()
                .eq(TestPlanItem::getPlanId, planId)
                .orderByAsc(TestPlanItem::getOrderIndex));
        if (items.isEmpty()) {
            return badRequest("计划为空，无法开始执行");
        }

        ManualRun run = new ManualRun();
        run.setPlanId(planId);
        run.setStatus(ManualRun.STATUS_IN_PROGRESS);
        run.setExecutedBy(principal.id());
        runMapper.insert(run);
        for (TestPlanItem item : items) {
            ManualRunItem runItem = new ManualRunItem();
            runItem.setRunId(run.getId());
            runItem.setPlanItemId(item.getId());
            runItem.setStatus(ManualRunItem.STATUS_PENDING);
            runItemMapper.insert(runItem);
        }

        auditService.record(authentication, "RUN_STARTED", null,
                Map.of("projectId", projectId, "planId", planId, "runId", run.getId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("runId", run.getId()));
    }

    @GetMapping("/api/v1/projects/{projectId}/plans/{planId}/runs")
    public List<Map<String, Object>> listRuns(@PathVariable Long projectId, @PathVariable Long planId,
                                              Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        return runMapper.selectList(new LambdaQueryWrapper<ManualRun>()
                        .eq(ManualRun::getPlanId, planId)
                        .orderByDesc(ManualRun::getId))
                .stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(), "status", r.getStatus(), "executedBy", String.valueOf(r.getExecutedBy())))
                .toList();
    }

    @GetMapping("/api/v1/projects/{projectId}/plans/{planId}/runs/{runId}")
    public ResponseEntity<?> runDetail(@PathVariable Long projectId, @PathVariable Long planId,
                                       @PathVariable Long runId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        ManualRun run = requireRun(planId, runId);
        return ResponseEntity.ok(runView(run));
    }

    @PutMapping("/api/v1/projects/{projectId}/plans/{planId}/runs/{runId}/items/{itemId}")
    @Transactional
    public ResponseEntity<?> recordItem(@PathVariable Long projectId, @PathVariable Long planId,
                                        @PathVariable Long runId, @PathVariable Long itemId,
                                        @RequestBody RecordRequest body, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        ManualRun run = requireRun(planId, runId);
        if (ManualRun.STATUS_COMPLETED.equals(run.getStatus())) {
            return badRequest("运行已完成，不能继续记录");
        }
        ManualRunItem item = runItemMapper.selectById(itemId);
        if (item == null || !item.getRunId().equals(runId)) {
            throw new ResourceNotFoundException("运行项不存在");
        }
        if (body.status() != null) {
            if (!ITEM_STATUSES.contains(body.status())) {
                return badRequest("状态取值不合法");
            }
            item.setStatus(body.status());
        }
        item.setActualResult(body.actualResult());
        item.setNote(body.note());
        item.setEvidenceUrl(body.evidenceUrl());
        item.setUpdatedAt(OffsetDateTime.now());
        runItemMapper.updateById(item);

        auditService.record(authentication, "RUN_ITEM_RECORDED", null,
                Map.of("projectId", projectId, "runId", runId, "itemId", itemId,
                        "status", item.getStatus()));
        return ResponseEntity.ok(runView(run));
    }

    @PostMapping("/api/v1/projects/{projectId}/plans/{planId}/runs/{runId}/complete")
    @Transactional
    public ResponseEntity<?> completeRun(@PathVariable Long projectId, @PathVariable Long planId,
                                         @PathVariable Long runId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        ManualRun run = requireRun(planId, runId);
        if (ManualRun.STATUS_COMPLETED.equals(run.getStatus())) {
            return badRequest("运行已完成");
        }
        run.setStatus(ManualRun.STATUS_COMPLETED);
        run.setCompletedAt(OffsetDateTime.now());
        runMapper.updateById(run);

        auditService.record(authentication, "RUN_COMPLETED", null,
                Map.of("projectId", projectId, "runId", runId));
        return ResponseEntity.ok(runView(run));
    }

    @GetMapping("/api/v1/projects/{projectId}/plans/{planId}/runs/{runId}/report")
    public ResponseEntity<?> report(@PathVariable Long projectId, @PathVariable Long planId,
                                    @PathVariable Long runId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        ManualRun run = requireRun(planId, runId);
        RunView view = runView(run);
        Map<String, Long> counts = view.items().stream()
                .collect(java.util.stream.Collectors.groupingBy(RunItemView::status,
                        java.util.stream.Collectors.counting()));
        ReportSummary summary = new ReportSummary(
                view.items().size(),
                counts.getOrDefault(ManualRunItem.STATUS_PASSED, 0L),
                counts.getOrDefault(ManualRunItem.STATUS_FAILED, 0L),
                counts.getOrDefault(ManualRunItem.STATUS_BLOCKED, 0L),
                counts.getOrDefault(ManualRunItem.STATUS_SKIPPED, 0L),
                counts.getOrDefault(ManualRunItem.STATUS_PENDING, 0L),
                view.executedBy(),
                view.startedAt(),
                view.completedAt(),
                run.getStatus());
        return ResponseEntity.ok(Map.of("summary", summary, "items", view.items()));
    }

    private boolean belongsToProject(TestCaseVersion snapshot, Long projectId) {
        TestCase testCase = testCaseMapper.selectById(snapshot.getCaseId());
        return testCase != null && testCase.getProjectId().equals(projectId);
    }

    private ManualRun requireRun(Long planId, Long runId) {
        ManualRun run = runMapper.selectById(runId);
        if (run == null || !run.getPlanId().equals(planId)) {
            throw new ResourceNotFoundException("运行不存在");
        }
        return run;
    }

    private RunView runView(ManualRun run) {
        List<ManualRunItem> runItems = runItemMapper.selectList(new LambdaQueryWrapper<ManualRunItem>()
                .eq(ManualRunItem::getRunId, run.getId()));
        List<TestPlanItem> planItems = planItemMapper.selectList(new LambdaQueryWrapper<TestPlanItem>()
                .eq(TestPlanItem::getPlanId, run.getPlanId())
                .orderByAsc(TestPlanItem::getOrderIndex));
        TestPlan plan = planMapper.selectById(run.getPlanId());

        List<RunItemView> itemViews = new ArrayList<>();
        for (ManualRunItem runItem : runItems) {
            TestPlanItem planItem = planItems.stream()
                    .filter(pi -> pi.getId().equals(runItem.getPlanItemId()))
                    .findFirst()
                    .orElse(null);
            if (planItem == null) {
                continue;
            }
            TestCaseVersion snapshot = versionMapper.selectById(planItem.getCaseVersionId());
            List<Map<String, Object>> steps = new ArrayList<>();
            if (snapshot != null) {
                try {
                    steps = objectMapper.readValue(snapshot.getStepsJson(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                } catch (Exception e) {
                    steps = List.of();
                }
            }
            itemViews.add(new RunItemView(runItem.getId(), runItem.getPlanItemId(),
                    planItem.getOrderIndex(), runItem.getStatus(), runItem.getActualResult(),
                    runItem.getNote(), runItem.getEvidenceUrl(),
                    snapshot == null ? "?" : snapshot.getTitle(),
                    snapshot == null ? 0 : snapshot.getVersion(), steps));
        }
        String executedBy = "?";
        return new RunView(run.getId(), run.getPlanId(), plan == null ? "?" : plan.getName(),
                run.getStatus(), executedBy, run.getStartedAt() == null ? null : run.getStartedAt().toString(),
                run.getCompletedAt() == null ? null : run.getCompletedAt().toString(), itemViews);
    }

    private PlanView planView(TestPlan plan) {
        List<TestPlanItem> items = planItemMapper.selectList(new LambdaQueryWrapper<TestPlanItem>()
                .eq(TestPlanItem::getPlanId, plan.getId())
                .orderByAsc(TestPlanItem::getOrderIndex));
        List<PlanItemView> views = items.stream().map(item -> {
            TestCaseVersion snapshot = versionMapper.selectById(item.getCaseVersionId());
            return new PlanItemView(item.getId(), item.getOrderIndex(), item.getCaseVersionId(),
                    snapshot == null ? "?" : snapshot.getTitle(),
                    snapshot == null ? 0 : snapshot.getVersion(),
                    snapshot == null ? "?" : snapshot.getStatus());
        }).toList();
        return new PlanView(plan.getId(), plan.getName(), plan.getStatus(), views);
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
