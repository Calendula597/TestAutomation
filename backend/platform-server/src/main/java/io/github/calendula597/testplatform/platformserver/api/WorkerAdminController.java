package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttempt;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionJob;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttemptMapper;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionJobMapper;
import io.github.calendula597.testplatform.platformserver.worker.JobQueueService;
import io.github.calendula597.testplatform.platformserver.worker.Worker;
import io.github.calendula597.testplatform.platformserver.worker.WorkerDiagnostic;
import io.github.calendula597.testplatform.platformserver.worker.WorkerDiagnosticMapper;
import io.github.calendula597.testplatform.platformserver.worker.WorkerMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin surface for worker registration, diagnostics and queue
 * observation. Session-authenticated and ADMIN-only; worker credentials
 * never appear in responses after registration or in audit records.
 */
@RestController
public class WorkerAdminController {

    private final WorkerMapper workerMapper;
    private final ExecutionJobMapper jobMapper;
    private final ExecutionAttemptMapper attemptMapper;
    private final WorkerDiagnosticMapper diagnosticMapper;
    private final JobQueueService queueService;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public WorkerAdminController(WorkerMapper workerMapper, ExecutionJobMapper jobMapper,
                                 ExecutionAttemptMapper attemptMapper, WorkerDiagnosticMapper diagnosticMapper,
                                 JobQueueService queueService, ObjectMapper objectMapper,
                                 AuditService auditService) {
        this.workerMapper = workerMapper;
        this.jobMapper = jobMapper;
        this.attemptMapper = attemptMapper;
        this.diagnosticMapper = diagnosticMapper;
        this.queueService = queueService;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public record RegisterRequest(String name, List<String> capabilities, String version, Integer maxConcurrency) {
    }

    public record TriggerRequest(Integer leaseSeconds, Integer timeoutSeconds) {
    }

    @PostMapping("/api/v1/system/workers")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> register(@RequestBody RegisterRequest body, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        JobQueueService.RegisteredWorker registered = queueService.registerWorker(
                body.name(), body.capabilities(), body.version(), body.maxConcurrency(), principal.id());

        // The token is returned exactly once and never persisted in clear.
        Map<String, Object> view = queueService.workerView(registered.worker());
        view.put("token", registered.token());
        auditService.record(authentication, "WORKER_REGISTERED", null,
                Map.of("workerId", registered.worker().getId(),
                        "capabilityCount", body.capabilities() == null ? 0 : body.capabilities().size()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/api/v1/system/workers")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listWorkers(Authentication authentication) {
        return workerMapper.selectList(new LambdaQueryWrapper<Worker>().orderByAsc(Worker::getId))
                .stream()
                .map(queueService::workerView)
                .toList();
    }

    @PostMapping("/api/v1/system/workers/{workerId}/diagnostics")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> triggerDiagnostic(@PathVariable Long workerId,
                                               @RequestBody(required = false) TriggerRequest body,
                                               Authentication authentication) {
        Integer leaseSeconds = body == null ? null : body.leaseSeconds();
        Integer timeoutSeconds = body == null ? null : body.timeoutSeconds();
        ExecutionJob job = queueService.triggerDiagnostic(workerId, leaseSeconds, timeoutSeconds);
        auditService.record(authentication, "WORKER_DIAGNOSTIC_TRIGGERED", null,
                Map.of("workerId", workerId, "jobId", job.getId()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.getId());
        response.put("status", job.getStatus());
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/api/v1/system/workers/{workerId}/diagnostics")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listDiagnostics(@PathVariable Long workerId) {
        return diagnosticMapper.selectList(new LambdaQueryWrapper<WorkerDiagnostic>()
                        .eq(WorkerDiagnostic::getWorkerId, workerId)
                        .orderByAsc(WorkerDiagnostic::getId))
                .stream()
                .map(d -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", d.getId());
                    view.put("jobId", d.getJobId());
                    view.put("status", d.getStatus());
                    view.put("checks", readJson(d.getChecks()));
                    view.put("createdAt", d.getCreatedAt() == null ? null : d.getCreatedAt().toString());
                    return view;
                })
                .toList();
    }

    @GetMapping("/api/v1/system/queue/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listJobs(@RequestParam(value = "status", required = false) String status) {
        LambdaQueryWrapper<ExecutionJob> query = new LambdaQueryWrapper<ExecutionJob>()
                .orderByAsc(ExecutionJob::getId);
        if (status != null && !status.isBlank()) {
            query.eq(ExecutionJob::getStatus, status);
        }
        return jobMapper.selectList(query)
                .stream()
                .map(job -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", job.getId());
                    view.put("jobType", job.getJobType());
                    view.put("workerId", job.getWorkerId());
                    view.put("status", job.getStatus());
                    view.put("createdAt", job.getCreatedAt() == null ? null : job.getCreatedAt().toString());
                    return view;
                })
                .toList();
    }

    @GetMapping("/api/v1/system/queue/jobs/{jobId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> jobDetail(@PathVariable Long jobId) {
        ExecutionJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new IdentityDtos.ErrorResponse(404, "任务不存在"));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", job.getId());
        view.put("jobType", job.getJobType());
        view.put("workerId", job.getWorkerId());
        view.put("status", job.getStatus());
        view.put("requeueOnLost", job.getRequeueOnLost());
        view.put("payload", readJson(job.getPayload()));
        view.put("attempts", attemptMapper.selectList(new LambdaQueryWrapper<ExecutionAttempt>()
                        .eq(ExecutionAttempt::getJobId, jobId)
                        .orderByAsc(ExecutionAttempt::getAttemptNumber))
                .stream()
                .map(attempt -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("attemptNumber", attempt.getAttemptNumber());
                    a.put("workerId", attempt.getWorkerId());
                    a.put("status", attempt.getStatus());
                    a.put("cancelRequested", attempt.getCancelRequested());
                    a.put("startedAt", attempt.getStartedAt() == null ? null : attempt.getStartedAt().toString());
                    a.put("finishedAt", attempt.getFinishedAt() == null ? null : attempt.getFinishedAt().toString());
                    return a;
                })
                .toList());
        return ResponseEntity.ok(view);
    }

    @GetMapping("/api/v1/system/queue/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> stats() {
        return queueService.stats();
    }

    @PostMapping("/api/v1/system/queue/jobs/{jobId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> cancel(@PathVariable Long jobId, Authentication authentication) {
        ExecutionJob job = queueService.cancelJob(jobId);
        auditService.record(authentication, "JOB_CANCELLED", null,
                Map.of("jobId", job.getId(), "jobType", job.getJobType()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", job.getId());
        response.put("status", job.getStatus());
        return ResponseEntity.ok(response);
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @ExceptionHandler({JobQueueService.QueueValidationException.class,
            JobQueueService.QueueNotFoundException.class,
            JobQueueService.QueueConflictException.class})
    public ResponseEntity<?> mapped(RuntimeException e) {
        if (e instanceof JobQueueService.QueueNotFoundException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new IdentityDtos.ErrorResponse(404, e.getMessage()));
        }
        if (e instanceof JobQueueService.QueueConflictException) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new IdentityDtos.ErrorResponse(409, e.getMessage()));
        }
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, e.getMessage()));
    }
}
