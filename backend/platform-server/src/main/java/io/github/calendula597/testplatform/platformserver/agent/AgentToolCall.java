package io.github.calendula597.testplatform.platformserver.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * Audit of one whitelisted tool invocation inside a draft generation run.
 */
@TableName("agent_tool_call")
public class AgentToolCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;

    private String toolName;

    private String inputSummary;

    private String outputSummary;

    private OffsetDateTime calledAt;

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

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public void setInputSummary(String inputSummary) {
        this.inputSummary = inputSummary;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public void setOutputSummary(String outputSummary) {
        this.outputSummary = outputSummary;
    }

    public OffsetDateTime getCalledAt() {
        return calledAt;
    }

    public void setCalledAt(OffsetDateTime calledAt) {
        this.calledAt = calledAt;
    }
}
