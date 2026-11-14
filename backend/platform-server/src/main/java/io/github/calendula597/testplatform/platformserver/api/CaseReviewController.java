package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Review and publish: OWNER compares the current draft against the last
 * published READY snapshot and appends a new immutable version. Only READY
 * versions are executable; draft edits never touch published rows.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/testcases/{caseId}/review")
public class CaseReviewController {

    private final ProjectAccessService access;
    private final TestCaseMapper testCaseMapper;
    private final TestCaseStepMapper stepMapper;
    private final TestCaseVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public CaseReviewController(ProjectAccessService access, TestCaseMapper testCaseMapper,
                                TestCaseStepMapper stepMapper, TestCaseVersionMapper versionMapper,
                                ObjectMapper objectMapper, AuditService auditService) {
        this.access = access;
        this.testCaseMapper = testCaseMapper;
        this.stepMapper = stepMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public record DiffStep(int stepOrder, String action, String testData, String expected) {
    }

    public record DiffSide(String title, String priority, Long requirementId, List<DiffStep> steps) {
    }

    public record DiffResponse(String previousStatus, DiffSide previous, DiffSide current) {
    }

    public record PublishRequest(String basis, Integer expectedVersion) {
    }

    public record VersionRow(int version, String status, String title, String priority,
                             String publishedBy, String publishedAt, String publishBasis, String origin) {
    }

    @GetMapping("/diff")
    public DiffResponse diff(@PathVariable Long projectId, @PathVariable Long caseId,
                             Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        TestCase draft = requireCase(projectId, caseId);

        List<DiffStep> currentSteps = stepMapper
                .selectList(new LambdaQueryWrapper<TestCaseStep>()
                        .eq(TestCaseStep::getCaseId, caseId)
                        .orderByAsc(TestCaseStep::getStepOrder))
                .stream()
                .map(s -> new DiffStep(s.getStepOrder(), s.getAction(), s.getTestData(), s.getExpected()))
                .toList();
        DiffSide current = new DiffSide(draft.getTitle(), draft.getPriority(),
                draft.getRequirementId(), currentSteps);

        TestCaseVersion previous = latestReady(caseId);
        if (previous == null) {
            return new DiffResponse("NONE", null, current);
        }
        DiffSide previousSide = new DiffSide(previous.getTitle(), previous.getPriority(),
                previous.getRequirementId(), readSteps(previous));
        return new DiffResponse(previous.getStatus(), previousSide, current);
    }

    @PostMapping("/publish")
    @Transactional
    public ResponseEntity<?> publish(@PathVariable Long projectId, @PathVariable Long caseId,
                                     @RequestBody PublishRequest body, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        // Publishing is reserved for the project OWNER; ordinary members —
        // including authors of Agent drafts once they exist — cannot publish.
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_OWNER);
        TestCase draft = requireCase(projectId, caseId);

        if (body.expectedVersion() == null || !body.expectedVersion().equals(draft.getVersion())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", 409, "message", "版本冲突：草稿已被他人修改，请刷新后重审"));
        }
        if (body.basis() == null || body.basis().isBlank()) {
            return badRequest("发布依据不能为空");
        }
        List<TestCaseStep> steps = stepMapper.selectList(new LambdaQueryWrapper<TestCaseStep>()
                .eq(TestCaseStep::getCaseId, caseId)
                .orderByAsc(TestCaseStep::getStepOrder));
        if (steps.isEmpty()) {
            return badRequest("至少需要一条步骤才能发布");
        }
        for (TestCaseStep step : steps) {
            if (step.getAction() == null || step.getAction().isBlank()
                    || step.getExpected() == null || step.getExpected().isBlank()) {
                return badRequest("每个步骤的操作与预期结果不能为空");
            }
        }

        TestCaseVersion previous = latestReady(caseId);
        int nextVersion = previous == null ? 1 : previous.getVersion() + 1;
        TestCaseVersion snapshot = new TestCaseVersion();
        snapshot.setCaseId(caseId);
        snapshot.setVersion(nextVersion);
        snapshot.setTitle(draft.getTitle());
        snapshot.setPriority(draft.getPriority());
        snapshot.setRequirementId(draft.getRequirementId());
        snapshot.setStatus(TestCaseVersion.STATUS_READY);
        snapshot.setStepsJson(writeSteps(steps));
        snapshot.setCreatedBy(draft.getCreatedBy());
        snapshot.setPublishedBy(principal.id());
        snapshot.setPublishBasis(body.basis().trim());
        snapshot.setOrigin(TestCaseVersion.ORIGIN_MANUAL);
        versionMapper.insert(snapshot);

        draft.setStatus(TestCaseVersion.STATUS_READY);
        testCaseMapper.updateById(draft);

        auditService.record(authentication, "CASE_PUBLISHED", null,
                Map.of("projectId", projectId, "testCaseId", caseId,
                        "version", nextVersion, "basis", body.basis().trim()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "version", nextVersion,
                "status", TestCaseVersion.STATUS_READY,
                "publishedBy", principal.getUsername()));
    }

    @GetMapping("/versions")
    public List<VersionRow> versions(@PathVariable Long projectId, @PathVariable Long caseId,
                                     Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        requireCase(projectId, caseId);
        return versionMapper.selectList(new LambdaQueryWrapper<TestCaseVersion>()
                        .eq(TestCaseVersion::getCaseId, caseId)
                        .orderByAsc(TestCaseVersion::getVersion))
                .stream()
                .map(v -> new VersionRow(v.getVersion(), v.getStatus(), v.getTitle(), v.getPriority(),
                        String.valueOf(v.getPublishedBy()), v.getPublishedAt().toString(),
                        v.getPublishBasis(), v.getOrigin()))
                .toList();
    }

    private TestCaseVersion latestReady(Long caseId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<TestCaseVersion>()
                .eq(TestCaseVersion::getCaseId, caseId)
                .orderByDesc(TestCaseVersion::getVersion)
                .last("LIMIT 1"));
    }

    private List<DiffStep> readSteps(TestCaseVersion snapshot) {
        try {
            return objectMapper.readValue(snapshot.getStepsJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, DiffStep.class));
        } catch (Exception e) {
            throw new IllegalStateException("历史版本步骤反序列化失败", e);
        }
    }

    private String writeSteps(List<TestCaseStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps.stream()
                    .map(s -> new DiffStep(s.getStepOrder(), s.getAction(), s.getTestData(), s.getExpected()))
                    .toList());
        } catch (Exception e) {
            throw new IllegalStateException("步骤序列化失败", e);
        }
    }

    private TestCase requireCase(Long projectId, Long caseId) {
        TestCase testCase = testCaseMapper.selectById(caseId);
        if (testCase == null || !testCase.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("用例不存在");
        }
        return testCase;
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
