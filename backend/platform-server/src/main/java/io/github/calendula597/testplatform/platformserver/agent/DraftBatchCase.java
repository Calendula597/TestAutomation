package io.github.calendula597.testplatform.platformserver.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * One draft case inside a batch. {@code suggestion} is NEW / DUPLICATE /
 * UPDATE; citations reference real retrieved knowledge versions only.
 */
@TableName("draft_batch_case")
public class DraftBatchCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;

    private String title;

    private String priority;

    private String stepsJson;

    private String technique;

    private String suggestion;

    private Long duplicateOfCaseId;

    private Boolean knowledgeInsufficient;

    private String citationsJson;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getStepsJson() {
        return stepsJson;
    }

    public void setStepsJson(String stepsJson) {
        this.stepsJson = stepsJson;
    }

    public String getTechnique() {
        return technique;
    }

    public void setTechnique(String technique) {
        this.technique = technique;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public Long getDuplicateOfCaseId() {
        return duplicateOfCaseId;
    }

    public void setDuplicateOfCaseId(Long duplicateOfCaseId) {
        this.duplicateOfCaseId = duplicateOfCaseId;
    }

    public Boolean getKnowledgeInsufficient() {
        return knowledgeInsufficient;
    }

    public void setKnowledgeInsufficient(Boolean knowledgeInsufficient) {
        this.knowledgeInsufficient = knowledgeInsufficient;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public void setCitationsJson(String citationsJson) {
        this.citationsJson = citationsJson;
    }
}
