package io.github.calendula597.testplatform.platformserver.automation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("execution_schedule")
public class ExecutionSchedule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long caseVersionId;
    private Long environmentId;
    private String name;
    private String cronExpression;
    private String timeZone;
    private Boolean allowOverlap;
    private Boolean enabled;
    private OffsetDateTime nextRunAt;
    private OffsetDateTime lastFiredAt;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getCaseVersionId() { return caseVersionId; }
    public void setCaseVersionId(Long caseVersionId) { this.caseVersionId = caseVersionId; }
    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public Boolean getAllowOverlap() { return allowOverlap; }
    public void setAllowOverlap(Boolean allowOverlap) { this.allowOverlap = allowOverlap; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public OffsetDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(OffsetDateTime nextRunAt) { this.nextRunAt = nextRunAt; }
    public OffsetDateTime getLastFiredAt() { return lastFiredAt; }
    public void setLastFiredAt(OffsetDateTime lastFiredAt) { this.lastFiredAt = lastFiredAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
