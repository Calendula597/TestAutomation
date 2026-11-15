package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.requirement.Requirement;
import io.github.calendula597.testplatform.platformserver.requirement.RequirementMapper;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Functional test case grid API. One row per case; structured steps are
 * edited through the expanded panel and saved with optimistic locking —
 * a stale expectedVersion yields an explicit 409, never silent overwrite.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/testcases")
public class TestCaseController {

    private static final Set<String> PRIORITIES =
            Set.of(TestCase.PRIORITY_HIGH, TestCase.PRIORITY_MEDIUM, TestCase.PRIORITY_LOW);

    private static final Set<String> CASE_TYPES = Set.of("MANUAL", "API", "WEB");

    private final ProjectAccessService access;
    private final TestCaseMapper testCaseMapper;
    private final TestCaseStepMapper stepMapper;
    private final RequirementMapper requirementMapper;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public TestCaseController(ProjectAccessService access, TestCaseMapper testCaseMapper,
                              TestCaseStepMapper stepMapper, RequirementMapper requirementMapper,
                              ObjectMapper objectMapper, AuditService auditService) {
        this.access = access;
        this.testCaseMapper = testCaseMapper;
        this.stepMapper = stepMapper;
        this.requirementMapper = requirementMapper;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public record StepInput(Integer stepOrder, String action, String testData, String expected) {
    }

    public record CreateRequest(String title, String priority, Long requirementId, List<StepInput> steps,
                                String caseType, Map<String, Object> execution) {
    }

    public record UpdateRequest(String title, String priority, Long requirementId,
                                Integer expectedVersion, List<StepInput> steps,
                                Map<String, Object> execution) {
    }

    public record BatchUpdateRequest(List<Long> ids, Map<String, String> fields, Integer expectedVersion) {
    }

    public record StepView(int stepOrder, String action, String testData, String expected) {
    }

    public record CaseView(Long id, String title, String priority, String status, int version,
                           Long requirementId, String requirementTitle, List<StepView> steps,
                           String caseType, Object execution) {
    }

    public record BatchResultRow(Long id, int status, String message, Integer newVersion) {
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@PathVariable Long projectId, @RequestBody CreateRequest body,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        ResponseEntity<?> validation = validate(body.title(), body.priority());
        if (validation != null) {
            return validation;
        }
        String caseType = body.caseType() == null ? "MANUAL" : body.caseType();
        if (!CASE_TYPES.contains(caseType)) {
            return badRequest("用例类型必须是 MANUAL、API 或 WEB");
        }
        String executionJson = null;
        if (body.execution() != null) {
            ResponseEntity<?> executionValidation = validateExecution(caseType, body.execution());
            if (executionValidation != null) {
                return executionValidation;
            }
            executionJson = objectMapper.writeValueAsString(body.execution());
        }

        TestCase testCase = new TestCase();
        testCase.setProjectId(projectId);
        testCase.setTitle(body.title().trim());
        testCase.setPriority(body.priority() == null ? TestCase.PRIORITY_MEDIUM : body.priority());
        testCase.setStatus(TestCase.STATUS_DRAFT);
        testCase.setCaseType(caseType);
        testCase.setExecutionJson(executionJson);
        testCase.setVersion(1);
        testCase.setRequirementId(requirementIdOf(projectId, body.requirementId()));
        testCase.setCreatedBy(principal.id());
        testCaseMapper.insert(testCase);
        replaceSteps(testCase.getId(), body.steps());

        auditService.record(authentication, "TESTCASE_CREATED", null,
                Map.of("projectId", projectId, "testCaseId", testCase.getId(), "caseType", caseType));
        return ResponseEntity.status(HttpStatus.CREATED).body(viewOf(testCase));
    }

    @GetMapping
    public List<CaseView> list(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        return testCaseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                        .eq(TestCase::getProjectId, projectId)
                        .orderByDesc(TestCase::getId))
                .stream()
                .map(this::viewOf)
                .toList();
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<?> detail(@PathVariable Long projectId, @PathVariable Long caseId,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        return ResponseEntity.ok(viewOf(requireCase(projectId, caseId)));
    }

    @PutMapping("/{caseId}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long projectId, @PathVariable Long caseId,
                                    @RequestBody UpdateRequest body, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        TestCase testCase = requireCase(projectId, caseId);

        if (body.expectedVersion() == null || !body.expectedVersion().equals(testCase.getVersion())) {
            return conflict(testCase.getVersion());
        }
        ResponseEntity<?> validation = validate(body.title(), body.priority());
        if (validation != null) {
            return validation;
        }

        testCase.setTitle(body.title().trim());
        testCase.setPriority(body.priority() == null ? testCase.getPriority() : body.priority());
        testCase.setRequirementId(requirementIdOf(projectId, body.requirementId()));
        if (body.execution() != null) {
            ResponseEntity<?> executionValidation = validateExecution(testCase.getCaseType(), body.execution());
            if (executionValidation != null) {
                return executionValidation;
            }
            testCase.setExecutionJson(objectMapper.writeValueAsString(body.execution()));
        }
        int updated = testCaseMapper.updateById(testCase);
        if (updated == 0) {
            return conflict(testCase.getVersion());
        }
        replaceSteps(caseId, body.steps());

        auditService.record(authentication, "TESTCASE_UPDATED", null,
                Map.of("projectId", projectId, "testCaseId", caseId, "newVersion", testCase.getVersion()));
        return ResponseEntity.ok(viewOf(requireCase(projectId, caseId)));
    }

    @PostMapping("/batch-update")
    @Transactional
    public ResponseEntity<?> batchUpdate(@PathVariable Long projectId, @RequestBody BatchUpdateRequest body,
                                         Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);

        if (body.ids() == null || body.ids().isEmpty()) {
            return badRequest("请选择要修改的用例");
        }
        Map<String, String> fields = body.fields() == null ? Map.of() : body.fields();
        if (fields.containsKey("priority") && !PRIORITIES.contains(fields.get("priority"))) {
            return badRequest("优先级取值不合法");
        }

        List<BatchResultRow> results = body.ids().stream().map(id -> {
            TestCase testCase = testCaseMapper.selectById(id);
            if (testCase == null || !testCase.getProjectId().equals(projectId)) {
                return new BatchResultRow(id, 404, "用例不存在", null);
            }
            if (body.expectedVersion() != null && !body.expectedVersion().equals(testCase.getVersion())) {
                return new BatchResultRow(id, 409, "版本冲突：他人已修改此用例", null);
            }
            applyFields(testCase, fields);
            int updated = testCaseMapper.updateById(testCase);
            if (updated == 0) {
                return new BatchResultRow(id, 409, "版本冲突：他人已修改此用例", null);
            }
            return new BatchResultRow(id, 200, null, testCase.getVersion());
        }).toList();

        auditService.record(authentication, "TESTCASE_BATCH_UPDATED", null,
                Map.of("projectId", projectId, "count", body.ids().size(),
                        "conflicts", results.stream().filter(r -> r.status() == 409).count()));
        return ResponseEntity.ok(Map.of("results", results));
    }

    private void applyFields(TestCase testCase, Map<String, String> fields) {
        fields.forEach((key, value) -> {
            if ("title".equals(key) && value != null && !value.isBlank()) {
                testCase.setTitle(value.trim());
            }
            if ("priority".equals(key) && value != null) {
                testCase.setPriority(value);
            }
        });
    }

    private Long requirementIdOf(Long projectId, Long requirementId) {
        if (requirementId == null) {
            return null;
        }
        Requirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null || !requirement.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("关联需求不存在");
        }
        return requirementId;
    }

    private TestCase requireCase(Long projectId, Long caseId) {
        TestCase testCase = testCaseMapper.selectById(caseId);
        if (testCase == null || !testCase.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("用例不存在");
        }
        return testCase;
    }

    private void replaceSteps(Long caseId, List<StepInput> steps) {
        stepMapper.delete(new LambdaQueryWrapper<TestCaseStep>().eq(TestCaseStep::getCaseId, caseId));
        if (steps == null) {
            return;
        }
        int order = 1;
        for (StepInput step : steps) {
            if (step.action() == null || step.action().isBlank()) {
                continue;
            }
            TestCaseStep row = new TestCaseStep();
            row.setCaseId(caseId);
            row.setStepOrder(step.stepOrder() == null ? order : step.stepOrder());
            row.setAction(step.action());
            row.setTestData(step.testData());
            row.setExpected(step.expected() == null ? "" : step.expected());
            stepMapper.insert(row);
            order++;
        }
    }

    private ResponseEntity<?> validateExecution(String caseType, Map<String, Object> execution) {
        if ("WEB".equals(caseType)) {
            return validateWebExecution(execution);
        }
        if (!"API".equals(caseType)) {
            return badRequest("只有 API 或 WEB 用例可以携带执行配置");
        }
        Object request = execution.get("request");
        Object requests = execution.get("requests");
        if (request instanceof Map<?, ?> requestMap) {
            ResponseEntity<?> invalid = validateRequest(requestMap);
            if (invalid != null) {
                return invalid;
            }
        } else if (requests instanceof List<?> chain) {
            if (chain.isEmpty()) {
                return badRequest("请求链不能为空");
            }
            for (Object step : chain) {
                if (!(step instanceof Map<?, ?> stepMap)
                        || !(stepMap.get("request") instanceof Map<?, ?> requestMap)) {
                    return badRequest("请求链的每个步骤必须包含 request");
                }
                ResponseEntity<?> invalid = validateRequest(requestMap);
                if (invalid != null) {
                    return invalid;
                }
            }
        } else {
            return badRequest("API 执行配置必须包含 request 或 requests");
        }
        Object assertions = execution.get("assertions");
        if (assertions != null && !(assertions instanceof List<?>)) {
            return badRequest("断言必须是数组");
        }
        return null;
    }

    private ResponseEntity<?> validateWebExecution(Map<String, Object> execution) {
        if (!(execution.get("startUrl") instanceof String startUrl) || startUrl.isBlank()) {
            return badRequest("Web 执行配置的起始地址不能为空");
        }
        if (!(execution.get("steps") instanceof List<?> steps) || steps.isEmpty()) {
            return badRequest("Web 执行配置必须包含至少一个步骤");
        }
        Set<String> actions = Set.of("NAVIGATE", "FILL", "CLICK", "ASSERT_TEXT", "ASSERT_VISIBLE",
                "ASSERT_TITLE", "UPLOAD", "DOWNLOAD", "WAIT", "FRAME", "MAIN_FRAME");
        Set<String> selectorActions = Set.of("FILL", "CLICK", "ASSERT_TEXT", "ASSERT_VISIBLE",
                "UPLOAD", "DOWNLOAD", "FRAME");
        for (Object item : steps) {
            if (!(item instanceof Map<?, ?> step)) {
                return badRequest("每个 Web 步骤必须是对象");
            }
            String action = String.valueOf(step.get("action")).toUpperCase();
            if (!actions.contains(action)) {
                return badRequest("不支持的 Web 步骤类型: " + action);
            }
            if (selectorActions.contains(action)
                    && (step.get("selector") == null || String.valueOf(step.get("selector")).isBlank())) {
                return badRequest(action + " 步骤必须提供 selector");
            }
        }
        return null;
    }

    private ResponseEntity<?> validateRequest(Map<?, ?> requestMap) {
        if (requestMap.get("method") == null
                || String.valueOf(requestMap.get("method")).isBlank()
                || requestMap.get("url") == null
                || String.valueOf(requestMap.get("url")).isBlank()) {
            return badRequest("每个请求必须包含 method 与 url");
        }
        return null;
    }

    private CaseView viewOf(TestCase testCase) {
        List<StepView> steps = stepMapper.selectList(new LambdaQueryWrapper<TestCaseStep>()
                        .eq(TestCaseStep::getCaseId, testCase.getId())
                        .orderByAsc(TestCaseStep::getStepOrder))
                .stream()
                .map(s -> new StepView(s.getStepOrder(), s.getAction(), s.getTestData(), s.getExpected()))
                .toList();
        String requirementTitle = null;
        if (testCase.getRequirementId() != null) {
            Requirement requirement = requirementMapper.selectById(testCase.getRequirementId());
            requirementTitle = requirement == null ? null : requirement.getTitle();
        }
        Object execution = null;
        if (testCase.getExecutionJson() != null) {
            try {
                execution = objectMapper.readValue(testCase.getExecutionJson(), Object.class);
            } catch (Exception e) {
                execution = null;
            }
        }
        return new CaseView(testCase.getId(), testCase.getTitle(), testCase.getPriority(),
                testCase.getStatus(), testCase.getVersion(), testCase.getRequirementId(),
                requirementTitle, steps, testCase.getCaseType(), execution);
    }

    private ResponseEntity<?> validate(String title, String priority) {
        if (title == null || title.isBlank() || title.length() > 200) {
            return badRequest("用例标题不能为空且不超过 200 字");
        }
        if (priority != null && !PRIORITIES.contains(priority)) {
            return badRequest("优先级取值不合法");
        }
        return null;
    }

    private ResponseEntity<?> conflict(int currentVersion) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", 409, "message", "版本冲突：他人已修改此用例，请刷新后基于最新版本重试",
                        "currentVersion", currentVersion));
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, message));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<IdentityDtos.ErrorResponse> notFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new IdentityDtos.ErrorResponse(404, e.getMessage()));
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<IdentityDtos.ErrorResponse> forbidden(InsufficientRoleException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new IdentityDtos.ErrorResponse(403, e.getMessage()));
    }
}
