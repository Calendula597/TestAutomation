package io.github.calendula597.testplatform.platformserver.reporting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttempt;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttemptMapper;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionJobMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class EvidenceRetentionService {

    private final EvidencePolicyMapper policyMapper;
    private final EvidencePrunedMapper prunedMapper;
    private final ExecutionAttemptMapper attemptMapper;
    private final ExecutionJobMapper jobMapper;
    private final Path evidenceRoot;

    public EvidenceRetentionService(EvidencePolicyMapper policyMapper, EvidencePrunedMapper prunedMapper,
                                    ExecutionAttemptMapper attemptMapper, ExecutionJobMapper jobMapper,
                                    @Value("${app.evidence.dir:./evidence}") String evidenceDir) {
        this.policyMapper = policyMapper;
        this.prunedMapper = prunedMapper;
        this.attemptMapper = attemptMapper;
        this.jobMapper = jobMapper;
        this.evidenceRoot = Path.of(evidenceDir).toAbsolutePath().normalize();
    }

    public int retentionDays(Long projectId) {
        EvidencePolicy policy = policyMapper.selectById(projectId);
        return policy == null ? 30 : policy.getRetentionDays();
    }

    @Transactional
    public void updateRetentionDays(Long projectId, int days, Long updatedBy) {
        if (days < 1 || days > 365) {
            throw new IllegalArgumentException("证据保留天数必须是 1-365 天");
        }
        EvidencePolicy policy = policyMapper.selectById(projectId);
        if (policy == null) {
            policy = new EvidencePolicy();
            policy.setProjectId(projectId);
            policy.setRetentionDays(days);
            policy.setUpdatedBy(updatedBy);
            policyMapper.insert(policy);
        } else {
            policy.setRetentionDays(days);
            policy.setUpdatedBy(updatedBy);
            policyMapper.updateById(policy);
        }
    }

    public boolean isPruned(String storageKey) {
        return prunedMapper.selectCount(new LambdaQueryWrapper<EvidencePruned>()
                .eq(EvidencePruned::getStorageKey, storageKey)) > 0;
    }

    @Transactional
    public Map<String, Object> sweepOnce() throws IOException {
        int deletedFiles = 0;
        long deletedBytes = 0;
        if (!Files.isDirectory(evidenceRoot)) {
            return Map.of("deletedFiles", 0, "deletedBytes", 0L);
        }
        try (var files = Files.walk(evidenceRoot)) {
            List<Path> candidates = files.filter(Files::isRegularFile)
                    .sorted(Comparator.reverseOrder()).toList();
            for (Path file : candidates) {
                String storageKey = evidenceRoot.relativize(file.toAbsolutePath().normalize())
                        .toString().replace(java.io.File.separatorChar, '/');
                if (isPruned(storageKey)) {
                    continue;
                }
                ExecutionAttempt attempt = attemptMapper.selectOne(new LambdaQueryWrapper<ExecutionAttempt>()
                        .like(ExecutionAttempt::getResult, storageKey).last("LIMIT 1"));
                if (attempt == null) {
                    continue;
                }
                var job = jobMapper.selectById(attempt.getJobId());
                if (job == null || job.getProjectId() == null) {
                    continue;
                }
                OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays(job.getProjectId()));
                if (!Files.getLastModifiedTime(file).toInstant().isBefore(cutoff.toInstant())) {
                    continue;
                }
                long size = Files.size(file);
                Files.deleteIfExists(file);
                EvidencePruned pruned = new EvidencePruned();
                pruned.setStorageKey(storageKey);
                pruned.setProjectId(job.getProjectId());
                prunedMapper.insert(pruned);
                deletedFiles++;
                deletedBytes += size;
            }
        }
        cleanupEmptyDirectories();
        return Map.of("deletedFiles", deletedFiles, "deletedBytes", deletedBytes);
    }

    @Scheduled(fixedDelayString = "${app.evidence.retention-sweep-ms:3600000}")
    public void scheduledSweep() throws IOException {
        sweepOnce();
    }

    private void cleanupEmptyDirectories() throws IOException {
        if (!Files.isDirectory(evidenceRoot)) {
            return;
        }
        try (var paths = Files.walk(evidenceRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                if (Files.isDirectory(path) && !path.equals(evidenceRoot)) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                }
            });
        }
    }
}
