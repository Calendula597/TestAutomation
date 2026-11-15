package io.github.calendula597.testplatform.platformserver.worker;

import io.github.calendula597.testplatform.platformserver.environment.Environment;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentMapper;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersion;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersionMapper;
import io.github.calendula597.testplatform.platformserver.environment.SecretCryptoService;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersion;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkerExecutionPreparationService {

    private final ExecutionAttemptMapper attemptMapper;
    private final ExecutionJobMapper jobMapper;
    private final TestCaseVersionMapper versionMapper;
    private final EnvironmentMapper environmentMapper;
    private final EnvironmentVersionMapper environmentVersionMapper;
    private final SecretCryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public WorkerExecutionPreparationService(ExecutionAttemptMapper attemptMapper, ExecutionJobMapper jobMapper,
                                             TestCaseVersionMapper versionMapper,
                                             EnvironmentMapper environmentMapper,
                                             EnvironmentVersionMapper environmentVersionMapper,
                                             SecretCryptoService cryptoService, ObjectMapper objectMapper) {
        this.attemptMapper = attemptMapper;
        this.jobMapper = jobMapper;
        this.versionMapper = versionMapper;
        this.environmentMapper = environmentMapper;
        this.environmentVersionMapper = environmentVersionMapper;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> prepare(Worker worker, Long attemptId) {
        ExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new JobQueueService.QueueNotFoundException("执行尝试不存在");
        }
        if (!worker.getId().equals(attempt.getWorkerId())) {
            throw new JobQueueService.QueueOwnershipException("执行尝试不属于当前 Worker");
        }
        if (!ExecutionAttempt.STATUS_RUNNING.equals(attempt.getStatus())) {
            throw new JobQueueService.QueueConflictException("执行尝试已结束");
        }
        ExecutionJob job = jobMapper.selectById(attempt.getJobId());
        Map<String, Object> payload = readMap(job.getPayload());
        long caseVersionId = requiredId(payload, "caseVersionId");
        long environmentId = requiredId(payload, "environmentId");

        TestCaseVersion version = versionMapper.selectById(caseVersionId);
        if (version == null || !TestCaseVersion.STATUS_READY.equals(version.getStatus())
                || !job.getJobType().equals(version.getCaseType()) || version.getExecutionJson() == null) {
            throw new JobQueueService.QueueValidationException("用例版本与任务类型不匹配或尚未发布");
        }
        Environment environment = environmentMapper.selectById(environmentId);
        if (environment == null || !job.getProjectId().equals(environment.getProjectId())) {
            throw new JobQueueService.QueueValidationException("环境与任务项目不匹配");
        }
        EnvironmentVersion environmentVersion = environmentVersionMapper.selectOne(
                new LambdaQueryWrapper<EnvironmentVersion>()
                        .eq(EnvironmentVersion::getEnvironmentId, environmentId)
                        .eq(EnvironmentVersion::getVersion, environment.getCurrentVersion()));
        if (environmentVersion == null) {
            throw new JobQueueService.QueueValidationException("环境版本数据缺失");
        }

        Map<String, String> variables = readStringMap(environmentVersion.getPlainVariables());
        Map<String, String> ciphertexts = readStringMap(environmentVersion.getSecrets());
        if (!ciphertexts.isEmpty() && !cryptoService.isConfigured()) {
            throw new JobQueueService.QueueValidationException("密钥加密未配置主密钥");
        }
        List<String> secretKeys = new ArrayList<>();
        for (Map.Entry<String, String> entry : ciphertexts.entrySet()) {
            variables.put(entry.getKey(), cryptoService.decrypt(entry.getValue()));
            secretKeys.add(entry.getKey());
        }

        Map<String, Object> caseVersion = new LinkedHashMap<>();
        caseVersion.put("id", version.getId());
        caseVersion.put("execution", readObject(version.getExecutionJson()));
        Map<String, Object> resolvedEnvironment = new LinkedHashMap<>();
        resolvedEnvironment.put("id", environmentId);
        resolvedEnvironment.put("variables", variables);
        resolvedEnvironment.put("secretKeys", secretKeys);
        resolvedEnvironment.put("targets", readObject(environmentVersion.getTargets()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobType", job.getJobType());
        response.put("caseVersion", caseVersion);
        response.put("environment", resolvedEnvironment);
        return response;
    }

    private long requiredId(Map<String, Object> payload, String key) {
        if (!(payload.get(key) instanceof Number number)) {
            throw new JobQueueService.QueueValidationException("任务缺少 " + key);
        }
        return number.longValue();
    }

    private Object readObject(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new JobQueueService.QueueValidationException("执行数据反序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new JobQueueService.QueueValidationException("任务数据反序列化失败");
        }
    }

    private Map<String, String> readStringMap(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            throw new JobQueueService.QueueValidationException("环境数据反序列化失败");
        }
    }
}
