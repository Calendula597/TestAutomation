package io.github.calendula597.testplatform.platformserver.automation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.environment.Environment;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentMapper;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.testcase.TestCase;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseMapper;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersion;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersionMapper;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionJob;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionJobMapper;
import io.github.calendula597.testplatform.platformserver.worker.JobQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ScheduleService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final Set<String> EXECUTABLE_TYPES = Set.of(
            "API", "WEB", "ELECTRON", "ANDROID", "WINDOWS_NATIVE", "PERFORMANCE", "WHITEBOX");

    private final ExecutionScheduleMapper scheduleMapper;
    private final TestCaseMapper testCaseMapper;
    private final TestCaseVersionMapper versionMapper;
    private final EnvironmentMapper environmentMapper;
    private final ExecutionJobMapper jobMapper;
    private final ProjectAccessService access;
    private final JobQueueService queueService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ScheduleService(ExecutionScheduleMapper scheduleMapper, TestCaseMapper testCaseMapper,
                           TestCaseVersionMapper versionMapper, EnvironmentMapper environmentMapper,
                           ExecutionJobMapper jobMapper, ProjectAccessService access,
                           JobQueueService queueService, JdbcTemplate jdbc,
                           PlatformTransactionManager transactionManager) {
        this.scheduleMapper = scheduleMapper;
        this.testCaseMapper = testCaseMapper;
        this.versionMapper = versionMapper;
        this.environmentMapper = environmentMapper;
        this.jobMapper = jobMapper;
        this.access = access;
        this.queueService = queueService;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record ScheduleCommand(Long caseId, Long environmentId, String name, String cronExpression,
                                  String timeZone, Boolean allowOverlap, Boolean enabled, Boolean confirmed) {
    }

    public static class ScheduleConflictException extends RuntimeException {
        public ScheduleConflictException(String message) {
            super(message);
        }
    }

    public ExecutionSchedule create(Long projectId, ScheduleCommand command, Long actorId) {
        TestCaseVersion version = resolve(projectId, command.caseId(), command.environmentId(), actorId,
                Boolean.TRUE.equals(command.confirmed()));
        validateName(command.name());
        ScheduleTiming timing = timing(command.cronExpression(), command.timeZone());
        long duplicates = scheduleMapper.selectCount(new LambdaQueryWrapper<ExecutionSchedule>()
                .eq(ExecutionSchedule::getProjectId, projectId)
                .eq(ExecutionSchedule::getCaseVersionId, version.getId())
                .eq(ExecutionSchedule::getEnvironmentId, command.environmentId())
                .eq(ExecutionSchedule::getCronExpression, timing.cron())
                .eq(ExecutionSchedule::getTimeZone, timing.zone().getId()));
        if (duplicates > 0) {
            throw new ScheduleConflictException("相同用例、环境、时刻和时区的定时计划已存在");
        }
        ExecutionSchedule schedule = new ExecutionSchedule();
        schedule.setProjectId(projectId);
        schedule.setCaseVersionId(version.getId());
        schedule.setEnvironmentId(command.environmentId());
        schedule.setName(command.name().trim());
        schedule.setCronExpression(timing.cron());
        schedule.setTimeZone(timing.zone().getId());
        schedule.setAllowOverlap(Boolean.TRUE.equals(command.allowOverlap()));
        schedule.setEnabled(command.enabled() == null || command.enabled());
        schedule.setNextRunAt(timing.next());
        schedule.setCreatedBy(actorId);
        try {
            scheduleMapper.insert(schedule);
        } catch (DataIntegrityViolationException e) {
            throw new ScheduleConflictException("相同用例、环境、时刻和时区的定时计划已存在");
        }
        return schedule;
    }

    public List<ExecutionSchedule> list(Long projectId) {
        return scheduleMapper.selectList(new LambdaQueryWrapper<ExecutionSchedule>()
                .eq(ExecutionSchedule::getProjectId, projectId).orderByAsc(ExecutionSchedule::getId));
    }

    public ExecutionSchedule update(Long projectId, Long scheduleId, ScheduleCommand command, Long actorId) {
        ExecutionSchedule schedule = require(projectId, scheduleId);
        TestCaseVersion version = versionMapper.selectById(schedule.getCaseVersionId());
        if (version == null) {
            throw new IllegalStateException("定时计划的用例版本不可用");
        }
        if (command.caseId() != null && !version.getCaseId().equals(command.caseId())) {
            throw new IllegalArgumentException("计划创建后不能更换用例");
        }
        if (command.environmentId() != null && !schedule.getEnvironmentId().equals(command.environmentId())) {
            throw new IllegalArgumentException("计划创建后不能更换环境");
        }
        if ("PERFORMANCE".equals(version.getCaseType())) {
            access.requireRole(projectId, actorId, ProjectMember.ROLE_OWNER);
        }
        if (command.name() != null) {
            validateName(command.name());
            schedule.setName(command.name().trim());
        }
        if (command.cronExpression() != null || command.timeZone() != null) {
            ScheduleTiming timing = timing(command.cronExpression() == null ? schedule.getCronExpression()
                    : command.cronExpression(), command.timeZone() == null ? schedule.getTimeZone() : command.timeZone());
            schedule.setCronExpression(timing.cron());
            schedule.setTimeZone(timing.zone().getId());
            schedule.setNextRunAt(timing.next());
        }
        if (command.allowOverlap() != null) {
            schedule.setAllowOverlap(command.allowOverlap());
        }
        if (command.enabled() != null) {
            schedule.setEnabled(command.enabled());
            schedule.setNextRunAt(nextRun(schedule.getCronExpression(), ZoneId.of(schedule.getTimeZone())));
        }
        schedule.setUpdatedAt(OffsetDateTime.now());
        try {
            scheduleMapper.updateById(schedule);
        } catch (DataIntegrityViolationException e) {
            throw new ScheduleConflictException("相同用例、环境、时刻和时区的定时计划已存在");
        }
        return schedule;
    }

    public void disable(Long projectId, Long scheduleId) {
        ExecutionSchedule schedule = require(projectId, scheduleId);
        schedule.setEnabled(false);
        schedule.setUpdatedAt(OffsetDateTime.now());
        scheduleMapper.updateById(schedule);
    }

    public ExecutionSchedule require(Long projectId, Long scheduleId) {
        ExecutionSchedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null || !projectId.equals(schedule.getProjectId())) {
            throw new ResourceNotFoundException("定时计划不存在");
        }
        return schedule;
    }

    public void triggerDueSchedules() {
        List<ExecutionSchedule> due = scheduleMapper.selectList(new LambdaQueryWrapper<ExecutionSchedule>()
                .eq(ExecutionSchedule::getEnabled, true)
                .le(ExecutionSchedule::getNextRunAt, OffsetDateTime.now())
                .orderByAsc(ExecutionSchedule::getNextRunAt).orderByAsc(ExecutionSchedule::getId)
                .last("LIMIT 100"));
        for (ExecutionSchedule schedule : due) {
            try {
                transactions.executeWithoutResult(status -> fireIfStillDue(schedule));
            } catch (RuntimeException e) {
                log.warn("Unable to trigger execution schedule {}", schedule.getId(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.execution.schedule-sweep-ms:1000}", initialDelay = 1000)
    public void scheduledTrigger() {
        triggerDueSchedules();
    }

    private void fireIfStillDue(ExecutionSchedule schedule) {
        OffsetDateTime following = nextRun(schedule.getCronExpression(), ZoneId.of(schedule.getTimeZone()));
        int claimed = jdbc.update("UPDATE execution_schedule SET next_run_at = ? WHERE id = ? AND next_run_at = ?",
                following, schedule.getId(), schedule.getNextRunAt());
        if (claimed == 0) {
            return;
        }
        if (!Boolean.TRUE.equals(schedule.getAllowOverlap())) {
            long active = jobMapper.selectCount(new LambdaQueryWrapper<ExecutionJob>()
                    .eq(ExecutionJob::getScheduleId, schedule.getId())
                    .in(ExecutionJob::getStatus, ExecutionJob.STATUS_QUEUED, ExecutionJob.STATUS_RUNNING));
            if (active > 0) {
                return;
            }
        }
        TestCaseVersion version = versionMapper.selectById(schedule.getCaseVersionId());
        if (version == null || !TestCaseVersion.STATUS_READY.equals(version.getStatus())) {
            throw new IllegalStateException("定时计划的已发布用例版本不可用");
        }
        if ("PERFORMANCE".equals(version.getCaseType())) {
            access.requireRole(schedule.getProjectId(), schedule.getCreatedBy(), ProjectMember.ROLE_OWNER);
        }
        ExecutionJob job = queueService.enqueueExecution(schedule.getProjectId(), version.getCaseType(),
                Map.of("caseVersionId", schedule.getCaseVersionId(), "environmentId", schedule.getEnvironmentId()),
                schedule.getCreatedBy(), null, schedule.getId(), null);
        jdbc.update("UPDATE execution_schedule SET last_fired_at = now(), updated_at = now() WHERE id = ?",
                schedule.getId());
    }

    private TestCaseVersion resolve(Long projectId, Long caseId, Long environmentId, Long actorId,
                                    boolean performanceConfirmed) {
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
            throw new IllegalArgumentException("定时计划需要已发布的可执行用例版本");
        }
        Environment environment = environmentMapper.selectById(environmentId);
        if (environment == null || !projectId.equals(environment.getProjectId())) {
            throw new IllegalArgumentException("环境不存在或不属于当前项目");
        }
        if ("PERFORMANCE".equals(version.getCaseType())) {
            access.requireRole(projectId, actorId, ProjectMember.ROLE_OWNER);
            if (!performanceConfirmed) {
                throw new IllegalArgumentException("定时压测必须由项目负责人明确确认");
            }
        }
        return version;
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new IllegalArgumentException("计划名称不能为空且不超过 120 字");
        }
    }

    private record ScheduleTiming(String cron, ZoneId zone, OffsetDateTime next) {
    }

    private ScheduleTiming timing(String cron, String timeZone) {
        try {
            CronExpression.parse(cron);
            ZoneId zone = ZoneId.of(timeZone);
            return new ScheduleTiming(cron.trim(), zone, nextRun(cron, zone));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cron 必须为秒、分、时、日、月、周六段表达式，时区必须有效");
        }
    }

    private OffsetDateTime nextRun(String cron, ZoneId zone) {
        ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.now(zone));
        if (next == null) {
            throw new IllegalArgumentException("定时计划没有下一次执行时间");
        }
        return next.toOffsetDateTime();
    }

    public static Map<String, Object> view(ExecutionSchedule schedule) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", schedule.getId());
        view.put("projectId", schedule.getProjectId());
        view.put("caseVersionId", schedule.getCaseVersionId());
        view.put("environmentId", schedule.getEnvironmentId());
        view.put("name", schedule.getName());
        view.put("cronExpression", schedule.getCronExpression());
        view.put("timeZone", schedule.getTimeZone());
        view.put("allowOverlap", schedule.getAllowOverlap());
        view.put("enabled", schedule.getEnabled());
        view.put("nextRunAt", schedule.getNextRunAt().toString());
        view.put("lastFiredAt", schedule.getLastFiredAt() == null ? null : schedule.getLastFiredAt().toString());
        return view;
    }
}
