package io.github.calendula597.testplatform.platformserver.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * A clarification/coverage session for one requirement. Status flow:
 * ANALYZING -> WAITING_CLARIFICATION -> COVERAGE_CONFIRMATION ->
 * READY_FOR_DRAFT, with CANCELLED/FAILED terminal states. All state is
 * persisted so refresh and restart resume in place.
 */
@TableName("agent_session")
public class AgentSession {

    public static final String STATUS_ANALYZING = "ANALYZING";
    public static final String STATUS_WAITING_CLARIFICATION = "WAITING_CLARIFICATION";
    public static final String STATUS_COVERAGE_CONFIRMATION = "COVERAGE_CONFIRMATION";
    public static final String STATUS_READY_FOR_DRAFT = "READY_FOR_DRAFT";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long requirementId;

    private String status;

    private String errorMessage;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Long createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(Long requirementId) {
        this.requirementId = requirementId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
