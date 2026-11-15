package io.github.calendula597.testplatform.platformserver.automation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.environment.Environment;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentMapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CiExecutionService {
    private static final Set<String> EXECUTABLE_TYPES = Set.of(
            "API", "WEB", "ELECTRON", "ANDROID", "WINDOWS_NATIVE", "WHITEBOX");

    private final TestCaseMapper testCaseMapper;
    private final TestCaseVersionMapper versionMapper;
    private final EnvironmentMapper environmentMapper;
    private final ExecutionJobMapper jobMapper;
    private final ExecutionAttemptMapper attemptMapper;
    private final JobQueueService queueService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CiExecutionService(TestCaseMapper testCaseMapper, TestCaseVersionMapper versionMapper,
                              EnvironmentMapper environmentMapper, ExecutionJobMapper jobMapper,
                              ExecutionAttemptMapper attemptMapper, JobQueueService queueService,
                              AuditService auditService, ObjectMapper objectMapper) {
        this.testCaseMapper = testCaseMapper;
        this.versionMapper = versionMapper;
        this.environmentMapper = environmentMapper;
        this.jobMapper = jobMapper;
        this.attemptMapper = attemptMapper;
        this.queueService = queueService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public record TriggerResult(ExecutionJob job, boolean idempotent) {
    }

    public TriggerResult trigger(CiToken token, Long caseId, Long environmentId, Integer timeoutSeconds,
                                 String idempotencyKey) {
        Long projectId = token.getProjectId();
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("Idempotency-Key 必须为 8-128 位字母、数字、点、下划线、冒号或连字符");
        }
        if (timeoutSeconds != null && (timeoutSeconds < 1 || timeoutSeconds > 3600)) {
            throw new IllegalArgumentException("超时时长必须是 1-3600 秒");
        }
        if (caseId == null || environmentId == null) {
            throw new IllegalArgumentException("必须指定用例与环境");
        }
        TestCase testCase = testCaseMapper.selectById(caseId);
        if (testCase == null || !projectId.equals(testCase.getProjectId())) {
            throw new ResourceNotFoundException("用例不存在");
        }
        TestCaseVersion version = versionMapper.selectOne(new LambdaQueryWrapper<TestCaseVersion>()
                .eq(TestCaseVersion::getCaseId, caseId).eq(TestCaseVersion::getStatus, TestCaseVersion.STATUS_READY)
                .orderByDesc(TestCaseVersion::getVersion).last("LIMIT 1"));
        if (version == null || version.getExecutionJson() == null
                || !EXECUTABLE_TYPES.contains(version.getCaseType())) {
            throw new IllegalArgumentException("CI 触发需要已发布的非压测可执行用例；压测必须由项目负责人单独确认");
        }
        Environment environment = environmentMapper.selectById(environmentId);
        if (environment == null || !projectId.equals(environment.getProjectId())) {
            throw new IllegalArgumentException("环境不存在或不属于当前项目");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseVersionId", version.getId());
        payload.put("environmentId", environmentId);
        TriggerResult existing = matchingExisting(projectId, version, environmentId, idempotencyKey);
        if (existing != null) {
            return existing;
        }
        try {
            ExecutionJob job = queueService.enqueueExecution(projectId, version.getCaseType(), payload,
                    token.getCreatedBy(), timeoutSeconds, null, idempotencyKey);
            auditService.record(null, "CI_EXECUTION_TRIGGERED", null, Map.of(
                    "projectId", projectId, "jobId", job.getId(), "ciTokenId", token.getId(),
                    "caseVersionId", version.getId(), "environmentId", environmentId));
            return new TriggerResult(job, false);
        } catch (DataIntegrityViolationException e) {
            TriggerResult duplicate = matchingExisting(projectId, version, environmentId, idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            throw e;
        }
    }

    private TriggerResult matchingExisting(Long projectId, TestCaseVersion version, Long environmentId,
                                           String idempotencyKey) {
        ExecutionJob existing = jobMapper.selectOne(new LambdaQueryWrapper<ExecutionJob>()
                .eq(ExecutionJob::getProjectId, projectId)
                .eq(ExecutionJob::getIdempotencyKey, idempotencyKey));
        if (existing == null) {
            return null;
        }
        Object payload = parse(existing.getPayload());
        if (!version.getCaseType().equals(existing.getJobType()) || !(payload instanceof Map<?, ?> map)
                || !version.getId().equals(number(map.get("caseVersionId")))
                || !environmentId.equals(number(map.get("environmentId")))) {
            throw new CiException(409, "幂等键已经用于不同的执行请求");
        }
        return new TriggerResult(existing, true);
    }

    public Map<String, Object> detail(Long projectId, Long jobId) {
        ExecutionJob job = jobMapper.selectById(jobId);
        if (job == null || !projectId.equals(job.getProjectId())) {
            throw new ResourceNotFoundException("执行任务不存在");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", job.getId());
        view.put("jobType", job.getJobType());
        view.put("status", job.getStatus());
        view.put("scheduleId", job.getScheduleId());
        view.put("createdAt", job.getCreatedAt() == null ? null : job.getCreatedAt().toString());
        view.put("updatedAt", job.getUpdatedAt() == null ? null : job.getUpdatedAt().toString());
        if (parse(job.getPayload()) instanceof Map<?, ?> payload) {
            view.put("caseVersionId", payload.get("caseVersionId"));
            view.put("environmentId", payload.get("environmentId"));
        }
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (ExecutionAttempt attempt : attemptMapper.selectList(new LambdaQueryWrapper<ExecutionAttempt>()
                .eq(ExecutionAttempt::getJobId, jobId).orderByAsc(ExecutionAttempt::getAttemptNumber))) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("attemptNumber", attempt.getAttemptNumber());
            result.put("status", attempt.getStatus());
            result.put("startedAt", attempt.getStartedAt() == null ? null : attempt.getStartedAt().toString());
            result.put("finishedAt", attempt.getFinishedAt() == null ? null : attempt.getFinishedAt().toString());
            Object parsed = parse(attempt.getResult());
            if (parsed instanceof Map<?, ?> wrapped && wrapped.containsKey("detail")) {
                parsed = wrapped.get("detail");
            }
            result.put("result", parsed);
            attempts.add(result);
        }
        view.put("attempts", attempts);
        return view;
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Object parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
