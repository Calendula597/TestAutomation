package io.github.calendula597.testplatform.platformserver.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * One proposed coverage point. Secondary assumptions stay visible until the
 * user confirms; confirmed points feed the draft generation (T11).
 */
@TableName("coverage_point")
public class CoveragePoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String description;

    private String technique;

    private Boolean isAssumption;

    private Boolean confirmed;

    private String userNote;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTechnique() {
        return technique;
    }

    public void setTechnique(String technique) {
        this.technique = technique;
    }

    public Boolean getIsAssumption() {
        return isAssumption;
    }

    public void setIsAssumption(Boolean isAssumption) {
        this.isAssumption = isAssumption;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }

    public String getUserNote() {
        return userNote;
    }

    public void setUserNote(String userNote) {
        this.userNote = userNote;
    }
}
