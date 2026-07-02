package com.yang.dataagent.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /** 按 id 倒序取最近 N 条（Pageable 控制 N），调用方再反转回时间正序 */
    List<ChatMessageEntity> findByConversationIdOrderByIdDesc(String conversationId, Pageable pageable);
}
