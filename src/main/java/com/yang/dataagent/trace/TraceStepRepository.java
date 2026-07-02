package com.yang.dataagent.trace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TraceStepRepository extends JpaRepository<TraceStepEntity, Long> {

    List<TraceStepEntity> findByTraceIdOrderByStepIndexAsc(Long traceId);

    long countByTraceId(Long traceId);
}
