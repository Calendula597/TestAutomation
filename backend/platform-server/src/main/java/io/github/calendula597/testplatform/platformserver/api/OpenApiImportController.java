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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI import: turns path operations into reviewable API case drafts.
 * Imported cases are plain DRAFT rows — publishing still requires the
 * project owner's review, and importing never enqueues execution. Requests
 * target {{baseUrl}} from the environment; path parameters become explicit
 * variables so an unparameterized execution fails visibly instead of
 * silently hitting a template URL.
 */
@RestController
public class OpenApiImportController {

    private static final int MAX_OPERATIONS = 50;
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    private final ProjectAccessService access;
    private final TestCaseMapper testCaseMapper;
    private final TestCaseStepMapper stepMapper;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public OpenApiImportController(ProjectAccessService access, TestCaseMapper testCaseMapper,
                                   TestCaseStepMapper stepMapper, ObjectMapper objectMapper,
                                   AuditService auditService) {
        this.access = access;
        this.testCaseMapper = testCaseMapper;
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public record ImportRequest(String definition) {
    }

    @PostMapping("/api/v1/projects/{projectId}/testcases/import-openapi")
    @Transactional
    public ResponseEntity<?> importOpenApi(@PathVariable Long projectId,
                                           @RequestBody ImportRequest body,
                                           Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        if (body.definition() == null || body.definition().isBlank()) {
            return badRequest("OpenAPI 定义不能为空");
        }
        JsonNode definition;
        try {
            definition = objectMapper.readTree(body.definition());
        } catch (Exception e) {
            return badRequest("OpenAPI 定义无法解析为 JSON");
        }
        JsonNode paths = definition.path("paths");
        if (!paths.isObject() || paths.isEmpty()) {
            return badRequest("OpenAPI 定义没有可导入的操作");
        }

        List<Map<String, Object>> generated = new ArrayList<>();
        for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
            String path = pathEntry.getKey();
            JsonNode operations = pathEntry.getValue();
            for (Map.Entry<String, JsonNode> opEntry : operations.properties()) {
                String method = opEntry.getKey().toLowerCase();
                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }
                if (generated.size() >= MAX_OPERATIONS) {
                    return badRequest("操作数量超过单次导入上限（" + MAX_OPERATIONS + "）");
                }
                generated.add(buildCase(path, method, opEntry.getValue()));
            }
        }
        if (generated.isEmpty()) {
            return badRequest("OpenAPI 定义没有可导入的操作");
        }

        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> draft : generated) {
            TestCase testCase = new TestCase();
            testCase.setProjectId(projectId);
            testCase.setTitle((String) draft.get("title"));
            testCase.setPriority(TestCase.PRIORITY_MEDIUM);
            testCase.setStatus(TestCase.STATUS_DRAFT);
            testCase.setCaseType("API");
            testCase.setExecutionJson((String) draft.get("executionJson"));
            testCase.setVersion(1);
            testCase.setCreatedBy(principal.id());
            testCaseMapper.insert(testCase);

            TestCaseStep step = new TestCaseStep();
            step.setCaseId(testCase.getId());
            step.setStepOrder(1);
            step.setAction((String) draft.get("stepAction"));
            step.setExpected("按断言校验响应");
            stepMapper.insert(step);
            ids.add(testCase.getId());
        }

        auditService.record(authentication, "OPENAPI_IMPORTED", null,
                Map.of("projectId", projectId, "created", ids.size()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("created", ids.size());
        response.put("caseIds", ids);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private Map<String, Object> buildCase(String path, String method, JsonNode operation) {
        String operationId = operation.path("operationId").asText("");
        String summary = operation.path("summary").asText("");
        String title = !operationId.isBlank() ? operationId
                : !summary.isBlank() ? summary
                : method.toUpperCase() + " " + path;

        String url = "{{baseUrl}}" + path.replaceAll("\\{([A-Za-z0-9_.-]+)}", "{{$1}}");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method.toUpperCase());
        request.put("url", url);
        request.put("headers", Map.of("Accept", "application/json"));

        String bodyType = null;
        JsonNode requestBody = operation.path("requestBody");
        if (!requestBody.isMissingNode() && requestBody.isObject()) {
            JsonNode content = requestBody.path("content");
            if (content.has("application/json")) {
                bodyType = "JSON";
            } else if (content.has("text/plain")) {
                bodyType = "TEXT";
            } else if (content.has("application/x-www-form-urlencoded")) {
                bodyType = "FORM";
            }
        }
        if (bodyType != null && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            request.put("bodyType", bodyType);
            request.put("body", "");
        }

        String expectedStatus = "200";
        JsonNode responses = operation.path("responses");
        for (Map.Entry<String, JsonNode> responseEntry : responses.properties()) {
            String code = responseEntry.getKey();
            if (code.matches("2\\d\\d")) {
                expectedStatus = code;
                break;
            }
        }
        List<Map<String, Object>> assertions = List.of(
                Map.of("type", "STATUS", "expected", expectedStatus));

        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("request", request);
        execution.put("assertions", assertions);

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("title", truncate(title, 200));
        draft.put("executionJson", objectMapper.writeValueAsString(execution));
        draft.put("stepAction", method.toUpperCase() + " " + path);
        return draft;
    }

    private String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
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
