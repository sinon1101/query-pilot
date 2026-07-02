package com.yang.dataagent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 一个对话（多轮问答的容器）。id 用服务端生成的 UUID，
 * 前端首次提问不传 conversationId，响应里带回，后续追问携带以延续上下文。
 */
@Entity
@Table(name = "conversation")
public class ConversationEntity {

    @Id
    @Column(length = 36)
    private String id;

    /** 取首个问题的前若干字，仅用于列表展示 */
    @Column(length = 64, nullable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected ConversationEntity() {
    }

    public ConversationEntity(String id, String title) {
        this.id = id;
        this.title = title;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
