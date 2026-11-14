package io.github.calendula597.testplatform.platformserver.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL-backed persistent queue for execution jobs. Jobs and attempts
 * survive restarts because all state lives in the database; claiming is a
 * single atomic statement with FOR UPDATE SKIP LOCKED so two workers can
 * never receive the same job. Attempt validation (ownership + terminal
 * state) prevents duplicate claims and stale-result overwrites.
 *
 * Lost-lease recovery honours the platform rule: side-effect-free
 * diagnostics may be requeued automatically, anything with uncertain
 * external effects waits for a user retry (requeue_on_lost = false).
 */
@Service
public class JobQueueService {

    /** Executor vocabulary a worker may declare (PRD #1 executors). */
    private static final Set<String> ALLOWED_CAPABILITIES = Set.of(
            "DIAGNOSTIC", "API", "WEB", "ELECTRON", "ANDROID", "WINDOWS_NATIVE",
            "PERFORMANCE", "WHITEBOX");

    private static final List<String> DEFAULT_DIAGNOSTIC_CHECKS =
            List.of("jvm", "model-provider", "chromium", "appium");

    private final WorkerMapper workerMapper;
    private final ExecutionJobMapper jobMapper;
    private final ExecutionAttemptMapper attemptMapper;
    private final WorkerDiagnosticMapper diagnosticMapper;
    private final WorkerCredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final int defaultLeaseSeconds;
    private final int offlineAfterSeconds;
    private final int diagnosticTimeoutSeconds;

    public JobQueueService(WorkerMapper workerMapper, ExecutionJobMapper jobMapper,
                           ExecutionAttemptMapper attemptMapper, WorkerDiagnosticMapper diagnosticMapper,
                           WorkerCredentialService credentialService, ObjectMapper objectMapper,
                           JdbcTemplate jdbcTemplate,
                           @Value("${app.worker.lease-seconds:30}") int defaultLeaseSeconds,
                           @Value("${app.worker.offline-after-seconds:90}") int offlineAfterSeconds,
                           @Value("${app.worker.diagnostic-timeout-seconds:120}") int diagnosticTimeoutSeconds) {
        this.workerMapper = workerMapper;
        this.jobMapper = jobMapper;
        this.attemptMapper = attemptMapper;
        this.diagnosticMapper = diagnosticMapper;
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.defaultLeaseSeconds = defaultLeaseSeconds;
        this.offlineAfterSeconds = offlineAfterSeconds;
        this.diagnosticTimeoutSeconds = diagnosticTimeoutSeconds;
    }

    public Set<String> allowedCapabilities() {
        return ALLOWED_CAPABILITIES;
    }

    public int offlineAfterSeconds() {
        return offlineAfterSeconds;
    }

    // ------------------------------------------------------------------
    // Registration and job creation
    // ------------------------------------------------------------------

    public record RegisteredWorker(Worker worker, String token) {
    }

    @Transactional
    public RegisteredWorker registerWorker(String name, List<String> capabilities, String version,
                                           Integer maxConcurrency, Long registeredBy) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new QueueValidationException("Worker 名称不能为空且不超过 100 字");
        }
        if (capabilities == null || capabilities.isEmpty()
                || !ALLOWED_CAPABILITIES.containsAll(capabilities)) {
            throw new QueueValidationException("Worker 能力必须来自允许的执行器列表");
        }
        if (maxConcurrency == null || maxConcurrency < 1 || maxConcurrency > 64) {
            throw new QueueValidationException("并发上限必须是 1-64");
        }
        if (version != null && version.length() > 100) {
            throw new QueueValidationException("版本号不超过 100 字");
        }
        Long duplicate = workerMapper.selectCount(new LambdaQueryWrapper<Worker>()
                .eq(Worker::getName, name.trim()));
        if (duplicate != null && duplicate > 0) {
            throw new QueueValidationException("同名 Worker 已存在");
        }
        Worker worker = new Worker();
        worker.setName(name.trim());
        worker.setCapabilities(writeJson(capabilities));
        worker.setVersion(version == null ? null : version.trim());
        worker.setMaxConcurrency(maxConcurrency);
        worker.setRegisteredBy(registeredBy);
        WorkerCredentialService.IssuedToken issued = credentialService.issueToken();
        worker.setTokenHash(issued.tokenHash());
        workerMapper.insert(worker);
        return new RegisteredWorker(worker, issued.token());
    }

    @Transactional
    public ExecutionJob triggerDiagnostic(Long workerId, Integer leaseSeconds, Integer timeoutSeconds) {
        Worker worker = workerMapper.selectById(workerId);
        if (worker == null) {
            throw new QueueNotFoundException("Worker 不存在");
        }
        if (leaseSeconds != null && (leaseSeconds < 1 || leaseSeconds > 3600)) {
            throw new QueueValidationException("租约时长必须是 1-3600 秒");
        }
        if (timeoutSeconds != null && (timeoutSeconds < 1 || timeoutSeconds > 3600)) {
            throw new QueueValidationException("超时时长必须是 1-3600 秒");
        }
        ExecutionJob job = new ExecutionJob();
        job.setJobType(ExecutionJob.TYPE_DIAGNOSTIC);
        job.setWorkerId(workerId);
        job.setPayload(writeJson(Map.of("checks", DEFAULT_DIAGNOSTIC_CHECKS)));
        job.setStatus(ExecutionJob.STATUS_QUEUED);
        job.setRequeueOnLost(true);
        job.setLeaseSeconds(leaseSeconds == null ? defaultLeaseSeconds : leaseSeconds);
        job.setTimeoutSeconds(timeoutSeconds == null ? diagnosticTimeoutSeconds : timeoutSeconds);
        jobMapper.insert(job);
        return job;
    }

    // ------------------------------------------------------------------
    // Worker protocol
    // ------------------------------------------------------------------

    public record ClaimedJob(ExecutionJob job, ExecutionAttempt attempt) {
    }

    @Transactional
    public ClaimedJob claim(Worker worker) {
        List<String> capabilities = readJsonArray(worker.getCapabilities());
        if (capabilities.isEmpty()) {
            return null;
        }
        String placeholders = capabilities.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>();
        args.add(worker.getId());
        capabilities.forEach(args::add);

        // One atomic statement: lock the next claimable job and flip it to
        // RUNNING, so concurrent claims can never receive the same job.
        String claimSql = "UPDATE execution_job SET status = 'RUNNING', updated_at = now() "
                + "WHERE id = ("
                + "  SELECT j.id FROM execution_job j"
                + "  WHERE j.status = 'QUEUED' AND (j.worker_id IS NULL OR j.worker_id = ?)"
                + "    AND j.job_type IN (" + placeholders + ")"
                + "  ORDER BY j.id"
                + "  FOR UPDATE SKIP LOCKED"
                + "  LIMIT 1"
                + ") RETURNING id, job_type, payload, lease_seconds, timeout_seconds";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(claimSql, args.toArray());
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        ExecutionJob job = new ExecutionJob();
        job.setId(((Number) row.get("id")).longValue());
        job.setJobType((String) row.get("job_type"));
        job.setPayload((String) row.get("payload"));
        job.setLeaseSeconds(((Number) row.get("lease_seconds")).intValue());
        Integer timeout = row.get("timeout_seconds") == null ? null
                : ((Number) row.get("timeout_seconds")).intValue();
        job.setTimeoutSeconds(timeout);
        job.setStatus(ExecutionJob.STATUS_RUNNING);

        int nextAttemptNumber = nextAttemptNumber(job.getId());
        OffsetDateTime now = OffsetDateTime.now();
        ExecutionAttempt attempt = new ExecutionAttempt();
        attempt.setJobId(job.getId());
        attempt.setAttemptNumber(nextAttemptNumber);
        attempt.setWorkerId(worker.getId());
        attempt.setLeaseToken(credentialService.newLeaseToken());
        attempt.setLeaseExpiresAt(now.plusSeconds(job.getLeaseSeconds()));
        attempt.setStatus(ExecutionAttempt.STATUS_RUNNING);
        attempt.setCancelRequested(false);
        attemptMapper.insert(attempt);
        return new ClaimedJob(job, attempt);
    }

    @Transactional
    public Worker heartbeat(Worker worker, List<Long> attemptIds) {
        worker.setLastHeartbeatAt(OffsetDateTime.now());
        workerMapper.updateById(worker);
        if (attemptIds != null) {
            for (Long attemptId : attemptIds) {
                renewLease(worker, attemptId);
            }
        }
        return worker;
    }

    /** Renewal is refused for attempts the worker does not currently own and run. */
    private void renewLease(Worker worker, Long attemptId) {
        ExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new QueueNotFoundException("尝试不存在");
        }
        if (!worker.getId().equals(attempt.getWorkerId())
                || !ExecutionAttempt.STATUS_RUNNING.equals(attempt.getStatus())) {
            throw new QueueOwnershipException("尝试不属于该 Worker 或已结束");
        }
        ExecutionJob job = jobMapper.selectById(attempt.getJobId());
        attempt.setLeaseExpiresAt(OffsetDateTime.now().plusSeconds(job.getLeaseSeconds()));
        attempt.setLastHeartbeatAt(OffsetDateTime.now());
        attemptMapper.updateById(attempt);
    }

    public record ReportedResult(String jobStatus, Long diagnosticId) {
    }

    @Transactional
    public ReportedResult reportResult(Worker worker, Long attemptId, String status,
                                       Map<String, Object> detail, List<Map<String, Object>> checks) {
        ExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new QueueNotFoundException("尝试不存在");
        }
        if (!worker.getId().equals(attempt.getWorkerId())) {
            throw new QueueOwnershipException("尝试不属于该 Worker");
        }
        if (!ExecutionAttempt.STATUS_RUNNING.equals(attempt.getStatus())) {
            throw new QueueConflictException("该尝试已结束，重复结果被拒绝");
        }
        Set<String> allowed = Set.of(ExecutionAttempt.STATUS_PASSED, ExecutionAttempt.STATUS_ASSERTION_FAILED,
                ExecutionAttempt.STATUS_ERROR, ExecutionAttempt.STATUS_CANCELLED,
                ExecutionAttempt.STATUS_TIMED_OUT);
        if (status == null || !allowed.contains(status)) {
            throw new QueueValidationException("结果状态必须是 PASSED、ASSERTION_FAILED、ERROR、CANCELLED 或 TIMED_OUT");
        }
        ExecutionJob job = jobMapper.selectById(attempt.getJobId());
        if (Boolean.TRUE.equals(attempt.getCancelRequested()) && !ExecutionAttempt.STATUS_CANCELLED.equals(status)) {
            throw new QueueConflictException("已取消的任务只接受取消结果");
        }

        OffsetDateTime now = OffsetDateTime.now();
        attempt.setStatus(status);
        attempt.setFinishedAt(now);
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        if (detail != null) {
            resultPayload.put("detail", detail);
        }
        attempt.setResult(writeJson(resultPayload));
        attemptMapper.updateById(attempt);

        // Cancellation dominates: a job cancelled from the control plane
        // stays CANCELLED regardless of what the worker reported.
        if (!ExecutionJob.STATUS_CANCELLED.equals(job.getStatus())) {
            job.setStatus(status);
            job.setUpdatedAt(now);
            jobMapper.updateById(job);
        }

        Long diagnosticId = null;
        if (checks != null && ExecutionJob.TYPE_DIAGNOSTIC.equals(job.getJobType())) {
            WorkerDiagnostic diagnostic = new WorkerDiagnostic();
            diagnostic.setWorkerId(worker.getId());
            diagnostic.setJobId(job.getId());
            diagnostic.setStatus(ExecutionJob.STATUS_PASSED.equals(status)
                    ? WorkerDiagnostic.STATUS_PASSED : WorkerDiagnostic.STATUS_ERROR);
            diagnostic.setChecks(writeJson(checks));
            diagnosticMapper.insert(diagnostic);
            diagnosticId = diagnostic.getId();
        }
        return new ReportedResult(job.getStatus(), diagnosticId);
    }

    // ------------------------------------------------------------------
    // Control-plane operations
    // ------------------------------------------------------------------

    @Transactional
    public ExecutionJob cancelJob(Long jobId) {
        ExecutionJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new QueueNotFoundException("任务不存在");
        }
        boolean wasRunning = ExecutionJob.STATUS_RUNNING.equals(job.getStatus());
        if (!ExecutionJob.STATUS_QUEUED.equals(job.getStatus()) && !wasRunning) {
            throw new QueueConflictException("任务已结束，不能取消");
        }
        if (wasRunning) {
            // Running attempts are told to abort; they must report
            // CANCELLED or lose their lease.
            List<ExecutionAttempt> running = attemptMapper.selectList(
                    new LambdaQueryWrapper<ExecutionAttempt>()
                            .eq(ExecutionAttempt::getJobId, jobId)
                            .eq(ExecutionAttempt::getStatus, ExecutionAttempt.STATUS_RUNNING));
            for (ExecutionAttempt attempt : running) {
                attempt.setCancelRequested(true);
                attemptMapper.updateById(attempt);
            }
        }
        job.setStatus(ExecutionJob.STATUS_CANCELLED);
        job.setUpdatedAt(OffsetDateTime.now());
        jobMapper.updateById(job);
        return job;
    }

    /** One sweeper pass: timeouts first, then lost leases and requeues. */
    @Transactional
    public void sweepOnce() {
        // 1. Attempts past their job timeout are TIMED_OUT — definitive.
        jdbcTemplate.update(
                "UPDATE execution_attempt a SET status = 'TIMED_OUT', finished_at = now(), "
                        + "result = '{\"reason\":\"TIMEOUT\"}' "
                        + "FROM execution_job j WHERE a.job_id = j.id AND a.status = 'RUNNING' "
                        + "AND j.timeout_seconds IS NOT NULL "
                        + "AND a.started_at + make_interval(secs => j.timeout_seconds) < now()");

        // 2. Jobs whose running attempt timed out close as TIMED_OUT.
        jdbcTemplate.update(
                "UPDATE execution_job SET status = 'TIMED_OUT', updated_at = now() "
                        + "WHERE status = 'RUNNING' "
                        + "AND EXISTS (SELECT 1 FROM execution_attempt a WHERE a.job_id = execution_job.id "
                        + "  AND a.status = 'TIMED_OUT') "
                        + "AND NOT EXISTS (SELECT 1 FROM execution_attempt a WHERE a.job_id = execution_job.id "
                        + "  AND a.status = 'RUNNING')");

        // 3. Attempts past lease expiry: cancellation wins, otherwise the
        //    attempt died as an infrastructure error.
        jdbcTemplate.update(
                "UPDATE execution_attempt SET status = 'CANCELLED', finished_at = now(), "
                        + "result = '{\"reason\":\"LOST_LEASE_AFTER_CANCEL\"}' "
                        + "WHERE status = 'RUNNING' AND cancel_requested = true "
                        + "AND lease_expires_at < now()");
        jdbcTemplate.update(
                "UPDATE execution_attempt SET status = 'ERROR', finished_at = now(), "
                        + "result = '{\"reason\":\"LOST_LEASE\"}' "
                        + "WHERE status = 'RUNNING' AND lease_expires_at < now()");

        // 4. Jobs left RUNNING without a live attempt: requeue only when
        //    the job type is side-effect-free, else surface the error.
        jdbcTemplate.update(
                "UPDATE execution_job SET status = CASE WHEN requeue_on_lost THEN 'QUEUED' ELSE 'ERROR' END, "
                        + "updated_at = now() "
                        + "WHERE status = 'RUNNING' "
                        + "AND NOT EXISTS (SELECT 1 FROM execution_attempt a WHERE a.job_id = execution_job.id "
                        + "  AND a.status = 'RUNNING')");
    }

    // ------------------------------------------------------------------
    // Queries for admin observation
    // ------------------------------------------------------------------

    public Map<String, Object> workerView(Worker worker) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", worker.getId());
        view.put("name", worker.getName());
        view.put("capabilities", readJsonArray(worker.getCapabilities()));
        view.put("version", worker.getVersion());
        view.put("maxConcurrency", worker.getMaxConcurrency());
        view.put("lastHeartbeatAt",
                worker.getLastHeartbeatAt() == null ? null : worker.getLastHeartbeatAt().toString());
        view.put("status", deriveStatus(worker));
        return view;
    }

    private String deriveStatus(Worker worker) {
        if (worker.getLastHeartbeatAt() == null) {
            return "NEVER_SEEN";
        }
        return worker.getLastHeartbeatAt()
                .isAfter(OffsetDateTime.now().minusSeconds(offlineAfterSeconds)) ? "ONLINE" : "OFFLINE";
    }

    public Map<String, Object> stats() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String status : List.of(ExecutionJob.STATUS_QUEUED, ExecutionJob.STATUS_RUNNING,
                ExecutionJob.STATUS_PASSED, ExecutionJob.STATUS_ASSERTION_FAILED, ExecutionJob.STATUS_ERROR,
                ExecutionJob.STATUS_CANCELLED, ExecutionJob.STATUS_TIMED_OUT)) {
            counts.put(status, 0);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, count(*) AS total FROM execution_job GROUP BY status");
        long total = 0;
        for (Map<String, Object> row : rows) {
            counts.put((String) row.get("status"), ((Number) row.get("total")).longValue());
            total += ((Number) row.get("total")).longValue();
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("counts", counts);
        stats.put("total", total);
        return stats;
    }

    private int nextAttemptNumber(Long jobId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(attempt_number), 0) FROM execution_attempt WHERE job_id = ?",
                Integer.class, jobId);
        return (max == null ? 0 : max) + 1;
    }

    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private List<String> readJsonArray(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("Worker 能力数据反序列化失败", e);
        }
    }

    // ------------------------------------------------------------------
    // Protocol exceptions carrying HTTP semantics
    // ------------------------------------------------------------------

    public static class QueueValidationException extends RuntimeException {
        public QueueValidationException(String message) {
            super(message);
        }
    }

    public static class QueueNotFoundException extends RuntimeException {
        public QueueNotFoundException(String message) {
            super(message);
        }
    }

    public static class QueueOwnershipException extends RuntimeException {
        public QueueOwnershipException(String message) {
            super(message);
        }
    }

    public static class QueueConflictException extends RuntimeException {
        public QueueConflictException(String message) {
            super(message);
        }
    }

    public static class WorkerAuthException extends RuntimeException {
        public WorkerAuthException(String message) {
            super(message);
        }
    }
}
