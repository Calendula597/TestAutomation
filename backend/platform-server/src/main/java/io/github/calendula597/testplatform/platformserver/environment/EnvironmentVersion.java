package io.github.calendula597.testplatform.platformserver.environment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * Immutable versioned snapshot of one environment: plain variables,
 * encrypted secret values and the target allowlist. API responses are
 * built from this row but secrets are only ever shown masked.
 */
@TableName("environment_version")
public class EnvironmentVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long environmentId;

    private Integer version;

    private String plainVariables;

    private String secrets;

    private String targets;

    private Long createdBy;

    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(Long environmentId) {
        this.environmentId = environmentId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getPlainVariables() {
        return plainVariables;
    }

    public void setPlainVariables(String plainVariables) {
        this.plainVariables = plainVariables;
    }

    public String getSecrets() {
        return secrets;
    }

    public void setSecrets(String secrets) {
        this.secrets = secrets;
    }

    public String getTargets() {
        return targets;
    }

    public void setTargets(String targets) {
        this.targets = targets;
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
}
