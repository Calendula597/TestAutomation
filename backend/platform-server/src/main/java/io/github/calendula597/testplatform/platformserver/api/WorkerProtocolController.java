package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttempt;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttemptMapper;
import io.github.calendula597.testplatform.platformserver.worker.JobQueueService;
import io.github.calendula597.testplatform.platformserver.worker.Worker;
import io.github.calendula597.testplatform.platformserver.worker.WorkerCredentialService;
import io.github.calendula597.testplatform.platformserver.worker.WorkerMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Worker protocol: the only surface a worker credential can reach. Every
 * endpoint re-validates the Bearer credential server-side — the token
 * grants no session, no admin surface and no browser API. Responses to a
 * claim include the lease token and lease expiry the worker must honor.
 */
@RestController
public class WorkerProtocolController {

    private final WorkerMapper workerMapper;
    private final ExecutionAttemptMapper attemptMapper;
    private final JobQueueService queueService;
    private final WorkerCredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final io.github.calendula597.testplatform.platformserver.worker.WorkerExecutionPreparationService preparationService;

    public WorkerProtocolController(WorkerMapper workerMapper, ExecutionAttemptMapper attemptMapper,
                                    JobQueueService queueService, WorkerCredentialService credentialService,
                                    ObjectMapper objectMapper,
                                    io.github.calendula597.testplatform.platformserver.worker.WorkerExecutionPreparationService preparationService) {
        this.workerMapper = workerMapper;
        this.attemptMapper = attemptMapper;
        this.queueService = queueService;
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        this.preparationService = preparationService;
    }

    public record HeartbeatRequest(List<Long> attemptIds) {
    }

    public record ResultRequest(String status, Map<String, Object> detail, List<Map<String, Object>> checks) {
    }

    @PostMapping("/api/v1/worker/v1/jobs/claim")
    public ResponseEntity<?> claim(HttpServletRequest request) {
        Worker worker = requireWorker(request);
        JobQueueService.ClaimedJob claimed = queueService.claim(worker);
        if (claimed == null) {
            return ResponseEntity.noContent().build();
        }
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", claimed.job().getId());
        job.put("type", claimed.job().getJobType());
        job.put("payload", readJson(claimed.job().getPayload()));
        job.put("leaseSeconds", claimed.job().getLeaseSeconds());
        job.put("timeoutSeconds", claimed.job().getTimeoutSeconds());
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("id", claimed.attempt().getId());
        attempt.put("attemptNumber", claimed.attempt().getAttemptNumber());
        attempt.put("leaseToken", claimed.attempt().getLeaseToken());
        attempt.put("leaseExpiresAt", claimed.attempt().getLeaseExpiresAt().toString());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("job", job);
        response.put("attempt", attempt);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/worker/v1/attempts/{attemptId}/execution")
    public Map<String, Object> prepareExecution(@PathVariable Long attemptId, HttpServletRequest request) {
        return preparationService.prepare(requireWorker(request), attemptId);
    }

    @PostMapping("/api/v1/worker/v1/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestBody HeartbeatRequest body, HttpServletRequest request) {
        Worker worker = requireWorker(request);
        Worker renewed = queueService.heartbeat(worker, body.attemptIds());
        List<Map<String, Object>> attempts = new ArrayList<>();
        if (body.attemptIds() != null) {
            for (Long attemptId : body.attemptIds()) {
                ExecutionAttempt attempt = attemptMapper.selectById(attemptId);
                if (attempt == null || !worker.getId().equals(attempt.getWorkerId())) {
                    continue;
                }
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("attemptId", attempt.getId());
                a.put("cancelRequested", attempt.getCancelRequested());
                a.put("status", attempt.getStatus());
                a.put("leaseExpiresAt", attempt.getLeaseExpiresAt() == null
                        ? null : attempt.getLeaseExpiresAt().toString());
                attempts.add(a);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workerId", renewed.getId());
        response.put("offlineAfterSeconds", queueService.offlineAfterSeconds());
        response.put("attempts", attempts);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/worker/v1/attempts/{attemptId}/result")
    public ResponseEntity<?> result(@PathVariable Long attemptId, @RequestBody ResultRequest body,
                                    HttpServletRequest request) {
        Worker worker = requireWorker(request);
        JobQueueService.ReportedResult reported = queueService.reportResult(
                worker, attemptId, body.status(), body.detail(), body.checks());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", true);
        response.put("jobStatus", reported.jobStatus());
        return ResponseEntity.ok(response);
    }

    /**
     * Bearer credential resolution. The raw token is hashed and matched
     * against the stored digest; unknown or malformed credentials fail
     * with a uniform 401 that never reveals which part was wrong.
     */
    private Worker requireWorker(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new JobQueueService.WorkerAuthException("缺少 Worker 凭据");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty() || token.length() > 200) {
            throw new JobQueueService.WorkerAuthException("Worker 凭据无效");
        }
        Worker worker = workerMapper.selectOne(new LambdaQueryWrapper<Worker>()
                .eq(Worker::getTokenHash, credentialService.hash(token)));
        if (worker == null) {
            throw new JobQueueService.WorkerAuthException("Worker 凭据无效");
        }
        return worker;
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @ExceptionHandler({JobQueueService.WorkerAuthException.class,
            JobQueueService.QueueValidationException.class,
            JobQueueService.QueueNotFoundException.class,
            JobQueueService.QueueOwnershipException.class,
            JobQueueService.QueueConflictException.class})
    public ResponseEntity<?> mapped(RuntimeException e) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (e instanceof JobQueueService.WorkerAuthException) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (e instanceof JobQueueService.QueueOwnershipException) {
            status = HttpStatus.FORBIDDEN;
        } else if (e instanceof JobQueueService.QueueNotFoundException) {
            status = HttpStatus.NOT_FOUND;
        } else if (e instanceof JobQueueService.QueueConflictException) {
            status = HttpStatus.CONFLICT;
        } else if (e instanceof JobQueueService.QueueValidationException) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .body(new IdentityDtos.ErrorResponse(status.value(), e.getMessage()));
    }
}
