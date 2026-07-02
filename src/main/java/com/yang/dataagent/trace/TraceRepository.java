package com.yang.dataagent.trace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TraceRepository extends JpaRepository<TraceEntity, Long> {

    List<TraceEntity> findByConversationIdOrderByIdAsc(String conversationId);
}
