package com.yang.dataagent.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 执行轨迹中的一步，对应 AgentStep：模型思考文本或一次工具调用。
 */
@Entity
@Table(name = "agent_trace_step", indexes = @Index(name = "idx_step_trace", columnList = "traceId, stepIndex"))
public class TraceStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long traceId;

    /** 在整条轨迹中的序号（0 起），回放按它排序 */
    @Column(nullable = false)
    private int stepIndex;

    /** ReAct 第几轮（1 起） */
    @Column(nullable = false)
    private int round;

    /** thought / tool_call */
    @Column(length = 16, nullable = false)
    private String type;

    /** 工具名，thought 时为 null */
    @Column(length = 64)
    private String toolName;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String input;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String output;

    @Column(nullable = false)
    private boolean error;

    protected TraceStepEntity() {
    }

    public TraceStepEntity(Long traceId, int stepIndex, int round, String type,
                           String toolName, String input, String output, boolean error) {
        this.traceId = traceId;
        this.stepIndex = stepIndex;
        this.round = round;
        this.type = type;
        this.toolName = toolName;
        this.input = input;
        this.output = output;
        this.error = error;
    }

    public Long getId() {
        return id;
    }

    public Long getTraceId() {
        return traceId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public int getRound() {
        return round;
    }

    public String getType() {
        return type;
    }

    public String getToolName() {
        return toolName;
    }

    public String getInput() {
        return input;
    }

    public String getOutput() {
        return output;
    }

    public boolean isError() {
        return error;
    }
}
