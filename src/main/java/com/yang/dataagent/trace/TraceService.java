package com.yang.dataagent.trace;

import com.yang.dataagent.agent.AgentResult;
import com.yang.dataagent.agent.AgentStep;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行轨迹落库与回放查询。
 */
@Service
public class TraceService {

    private final TraceRepository traceRepository;
    private final TraceStepRepository stepRepository;

    public TraceService(TraceRepository traceRepository, TraceStepRepository stepRepository) {
        this.traceRepository = traceRepository;
        this.stepRepository = stepRepository;
    }

    /** 一次 Agent 执行结束后整体落库，返回 traceId */
    @Transactional
    public Long save(String conversationId, String question, AgentResult result, long durationMs) {
        TraceEntity trace = traceRepository.save(new TraceEntity(
                conversationId, question, result.success(), result.answer(), result.sql(), durationMs));
        List<AgentStep> steps = result.steps();
        List<TraceStepEntity> entities = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            AgentStep s = steps.get(i);
            entities.add(new TraceStepEntity(trace.getId(), i, s.round(), s.type(),
                    s.name(), s.input(), s.output(), s.error()));
        }
        stepRepository.saveAll(entities);
        return trace.getId();
    }

    /** 轨迹回放视图 */
    public record TraceView(Long id, String conversationId, String question, boolean success,
                            String answer, String finalSql, long durationMs, String createdAt,
                            List<StepView> steps) {
    }

    public record StepView(int stepIndex, int round, String type, String toolName,
                           String input, String output, boolean error) {
    }

    /** 轨迹列表项（不含步骤详情） */
    public record TraceSummary(Long id, String conversationId, String question, boolean success,
                               long durationMs, String createdAt, int stepCount) {
    }

    @Transactional(readOnly = true)
    public TraceView getTrace(Long traceId) {
        TraceEntity trace = traceRepository.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("trace 不存在: " + traceId));
        List<StepView> steps = stepRepository.findByTraceIdOrderByStepIndexAsc(traceId).stream()
                .map(s -> new StepView(s.getStepIndex(), s.getRound(), s.getType(), s.getToolName(),
                        s.getInput(), s.getOutput(), s.isError()))
                .toList();
        return new TraceView(trace.getId(), trace.getConversationId(), trace.getQuestion(),
                trace.isSuccess(), trace.getAnswer(), trace.getFinalSql(), trace.getDurationMs(),
                trace.getCreatedAt().toString(), steps);
    }

    @Transactional(readOnly = true)
    public List<TraceSummary> listByConversation(String conversationId) {
        return traceRepository.findByConversationIdOrderByIdAsc(conversationId).stream()
                .map(t -> new TraceSummary(t.getId(), t.getConversationId(), t.getQuestion(),
                        t.isSuccess(), t.getDurationMs(), t.getCreatedAt().toString(),
                        (int) stepRepository.countByTraceId(t.getId())))
                .toList();
    }
}
