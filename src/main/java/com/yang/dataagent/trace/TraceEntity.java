package com.yang.dataagent.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 一次 Agent 执行的轨迹头：问题、结果、耗时。
 * 每个步骤存 TraceStepEntity，二者用 traceId 关联（不建 JPA 关系映射，查询显式可控）。
 */
@Entity
@Table(name = "agent_trace", indexes = @Index(name = "idx_trace_conversation", columnList = "conversationId, id"))
public class TraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36, nullable = false)
    private String conversationId;

    /** 显式 TEXT：Hibernate 对 @Lob + NOT NULL 的 String 会生成 tinytext（255 字节） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false)
    private boolean success;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String answer;

    /** 最后一次成功执行的 SQL，闲聊类问题为 null */
    @Column(columnDefinition = "TEXT")
    private String finalSql;

    @Column(nullable = false)
    private long durationMs;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected TraceEntity() {
    }

    public TraceEntity(String conversationId, String question, boolean success,
                       String answer, String finalSql, long durationMs) {
        this.conversationId = conversationId;
        this.question = question;
        this.success = success;
        this.answer = answer;
        this.finalSql = finalSql;
        this.durationMs = durationMs;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getQuestion() {
        return question;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getAnswer() {
        return answer;
    }

    public String getFinalSql() {
        return finalSql;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
