package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.environment.Environment;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentMapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.testcase.TestCase;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseMapper;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersion;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersionMapper;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttempt;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttemptMapper;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionJob;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionJobMapper;
import io.github.calendula597.testplatform.platformserver.worker.JobQueueService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution triggers and member-visible results for project test cases.
 * Only READY versions of executable case types are enqueueable; results
 * are stored already masked by the executor, so this controller never
 * handles secret material.
 */
@RestController
public class ExecutionController {

    private final ProjectAccessService access;
    private final TestCaseMapper testCaseMapper;
    private final TestCaseVersionMapper versionMapper;
    private final EnvironmentMapper environmentMapper;
    private final ExecutionJobMapper jobMapper;
    private final ExecutionAttemptMapper attemptMapper;
    private final JobQueueService queueService;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final io.github.calendula597.testplatform.platformserver.reporting.EvidenceStorage evidenceStorage;

    public ExecutionController(ProjectAccessService access, TestCaseMapper testCaseMapper,
                               TestCaseVersionMapper versionMapper,
                               EnvironmentMapper environmentMapper, ExecutionJobMapper jobMapper,
                               ExecutionAttemptMapper attemptMapper, JobQueueService queueService,
                               ObjectMapper objectMapper, AuditService auditService,
                               io.github.calendula597.testplatform.platformserver.reporting.EvidenceStorage evidenceStorage) {
        this.access = access;
        this.testCaseMapper = testCaseMapper;
        this.versionMapper = versionMapper;
        this.environmentMapper = environmentMapper;
        this.jobMapper = jobMapper;
        this.attemptMapper = attemptMapper;
        this.queueService = queueService;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.evidenceStorage = evidenceStorage;
    }

    public record TriggerRequest(Long caseId, Long environmentId, Integer timeoutSeconds) {
    }

    private static final Set<String> EXECUTABLE_CASE_TYPES = Set.of("API", "WEB");

    @PostMapping("/api/v1/projects/{projectId}/executions")
    @Transactional
    public ResponseEntity<?> trigger(@PathVariable Long projectId, @RequestBody TriggerRequest body,
                                     Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        if (body.caseId() == null || body.environmentId() == null) {
            return badRequest("必须指定用例与环境");
        }
        if (body.timeoutSeconds() != null && (body.timeoutSeconds() < 1 || body.timeoutSeconds() > 3600)) {
            return badRequest("超时时长必须是 1-3600 秒");
        }
        TestCase draftCase = requireCase(projectId, body.caseId());
        // Execution always targets the case's latest published version.
        TestCaseVersion version = versionMapper.selectOne(
                new LambdaQueryWrapper<TestCaseVersion>()
                        .eq(TestCaseVersion::getCaseId, draftCase.getId())
                        .eq(TestCaseVersion::getStatus, TestCaseVersion.STATUS_READY)
                        .orderByDesc(TestCaseVersion::getVersion)
                        .last("LIMIT 1"));
        if (version == null) {
            return badRequest("该用例还没有已发布版本");
        }
        if (!EXECUTABLE_CASE_TYPES.contains(version.getCaseType()) || version.getExecutionJson() == null) {
            return badRequest("该用例不是可执行的类型（API 或 WEB）");
        }
        Environment environment = environmentMapper.selectById(body.environmentId());
        if (environment == null || !projectId.equals(environment.getProjectId())) {
            return badRequest("环境不存在或不属于当前项目");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseVersionId", version.getId());
        payload.put("environmentId", environment.getId());
        ExecutionJob job = queueService.enqueueExecution(projectId, version.getCaseType(), payload,
                principal.id(), body.timeoutSeconds());

        auditService.record(authentication, "EXECUTION_TRIGGERED", null,
                Map.of("projectId", projectId, "jobId", job.getId(),
                        "caseVersionId", version.getId(), "environmentId", environment.getId(),
                        "jobType", version.getCaseType()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.getId());
        response.put("status", job.getStatus());
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/api/v1/projects/{projectId}/executions")
    public List<Map<String, Object>> list(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        return jobMapper.selectList(new LambdaQueryWrapper<ExecutionJob>()
                        .eq(ExecutionJob::getProjectId, projectId)
                        .orderByAsc(ExecutionJob::getId))
                .stream()
                .map(this::jobView)
                .toList();
    }

    @GetMapping("/api/v1/projects/{projectId}/executions/{jobId}")
    public ResponseEntity<?> detail(@PathVariable Long projectId, @PathVariable Long jobId,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        ExecutionJob job = jobMapper.selectById(jobId);
        if (job == null || !projectId.equals(job.getProjectId())) {
            throw new ResourceNotFoundException("执行任务不存在");
        }
        Map<String, Object> view = jobView(job);
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (ExecutionAttempt attempt : attemptMapper.selectList(
                new LambdaQueryWrapper<ExecutionAttempt>()
                        .eq(ExecutionAttempt::getJobId, jobId)
                        .orderByAsc(ExecutionAttempt::getAttemptNumber))) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("attemptNumber", attempt.getAttemptNumber());
            a.put("status", attempt.getStatus());
            a.put("startedAt", attempt.getStartedAt() == null ? null : attempt.getStartedAt().toString());
            a.put("finishedAt", attempt.getFinishedAt() == null ? null : attempt.getFinishedAt().toString());
            // The protocol wraps the executor outcome in {"detail":...};
            // the member view exposes the outcome directly.
            Object result = parseResult(attempt.getResult());
            if (result instanceof Map<?, ?> wrapped && wrapped.containsKey("detail")) {
                result = wrapped.get("detail");
            }
            a.put("result", result);
            attempts.add(a);
        }
        view.put("attempts", attempts);
        return ResponseEntity.ok(view);
    }

    @GetMapping("/api/v1/projects/{projectId}/executions/{jobId}/evidence/{evidenceId}")
    public ResponseEntity<org.springframework.core.io.Resource> evidence(
            @PathVariable Long projectId, @PathVariable Long jobId,
            @PathVariable String evidenceId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        ExecutionJob job = jobMapper.selectById(jobId);
        if (job == null || !projectId.equals(job.getProjectId())) {
            throw new ResourceNotFoundException("执行任务不存在");
        }
        Map<String, Object> metadata = null;
        for (ExecutionAttempt attempt : attemptMapper.selectList(new LambdaQueryWrapper<ExecutionAttempt>()
                .eq(ExecutionAttempt::getJobId, jobId).orderByDesc(ExecutionAttempt::getId))) {
            metadata = findEvidence(parseResult(attempt.getResult()), evidenceId);
            if (metadata != null) {
                break;
            }
        }
        if (metadata == null) {
            throw new ResourceNotFoundException("证据不存在");
        }
        try {
            org.springframework.core.io.Resource resource =
                    evidenceStorage.read(String.valueOf(metadata.get("path")));
            String filename = String.valueOf(metadata.getOrDefault("filename", resource.getFilename()));
            String contentType = String.valueOf(metadata.getOrDefault("contentType", "application/octet-stream"));
            return ResponseEntity.ok()
                    .contentLength(resource.contentLength())
                    .header("Content-Type", contentType)
                    .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
                            .filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                    .body(resource);
        } catch (IOException | RuntimeException e) {
            throw new ResourceNotFoundException("证据不存在或无法读取");
        }
    }

    private Map<String, Object> findEvidence(Object result, String evidenceId) {
        if (result instanceof Map<?, ?> map) {
            Object id = map.get("id");
            if (id != null && evidenceId.equals(String.valueOf(id))
                    && map.get("kind") != null && map.get("path") != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) map;
                return metadata;
            }
            for (Object value : map.values()) {
                Map<String, Object> found = findEvidence(value, evidenceId);
                if (found != null) {
                    return found;
                }
            }
        } else if (result instanceof List<?> list) {
            for (Object value : list) {
                Map<String, Object> found = findEvidence(value, evidenceId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private TestCase requireCase(Long projectId, Long caseId) {
        TestCase testCase = testCaseMapper.selectById(caseId);
        if (testCase == null || !projectId.equals(testCase.getProjectId())) {
            throw new ResourceNotFoundException("用例不存在");
        }
        return testCase;
    }

    private Map<String, Object> jobView(ExecutionJob job) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", job.getId());
        view.put("jobType", job.getJobType());
        view.put("status", job.getStatus());
        view.put("createdAt", job.getCreatedAt() == null ? null : job.getCreatedAt().toString());
        Object payload = parseResult(job.getPayload());
        if (payload instanceof Map<?, ?> payloadMap) {
            view.put("caseVersionId", payloadMap.get("caseVersionId"));
            view.put("environmentId", payloadMap.get("environmentId"));
        }
        return view;
    }

    private Object parseResult(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
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
