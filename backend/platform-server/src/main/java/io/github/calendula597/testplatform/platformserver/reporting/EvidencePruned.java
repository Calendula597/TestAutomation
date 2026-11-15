package io.github.calendula597.testplatform.platformserver.reporting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("evidence_pruned")
public class EvidencePruned {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String storageKey;
    private Long projectId;
    private OffsetDateTime prunedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public OffsetDateTime getPrunedAt() {
        return prunedAt;
    }

    public void setPrunedAt(OffsetDateTime prunedAt) {
        this.prunedAt = prunedAt;
    }
}
