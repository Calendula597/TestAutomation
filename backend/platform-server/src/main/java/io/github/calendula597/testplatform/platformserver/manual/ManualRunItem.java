package io.github.calendula597.testplatform.platformserver.manual;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * Step-level manual result for one plan item inside a run. Status values:
 * PENDING, PASSED, FAILED, BLOCKED, SKIPPED.
 */
@TableName("manual_run_item")
public class ManualRunItem {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private Long planItemId;

    private String status;

    private String actualResult;

    private String note;

    private String evidenceUrl;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Long getPlanItemId() {
        return planItemId;
    }

    public void setPlanItemId(Long planItemId) {
        this.planItemId = planItemId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getActualResult() {
        return actualResult;
    }

    public void setActualResult(String actualResult) {
        this.actualResult = actualResult;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getEvidenceUrl() {
        return evidenceUrl;
    }

    public void setEvidenceUrl(String evidenceUrl) {
        this.evidenceUrl = evidenceUrl;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
