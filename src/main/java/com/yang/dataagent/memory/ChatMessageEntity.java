package com.yang.dataagent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 对话中的一条消息。只存 user 问题和 assistant 最终回答——
 * 中间的工具调用属于执行轨迹（trace 模块），不进对话记忆。
 */
@Entity
@Table(name = "chat_message", indexes = @Index(name = "idx_conversation", columnList = "conversationId, id"))
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36, nullable = false)
    private String conversationId;

    /** user / assistant */
    @Column(length = 16, nullable = false)
    private String role;

    /** 显式 TEXT：Hibernate 对 @Lob + NOT NULL 的 String 会生成 tinytext（255 字节），中文回答必超长 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(String conversationId, String role, String content) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
