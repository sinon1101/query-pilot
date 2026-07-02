package com.yang.dataagent.web;

import com.yang.dataagent.agent.AgentExecutor;
import com.yang.dataagent.agent.AgentResult;
import com.yang.dataagent.agent.AgentStep;
import com.yang.dataagent.memory.ConversationService;
import com.yang.dataagent.trace.TraceService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话接口（同步 REST，第三阶段升级 SSE）。
 * 编排一轮问答的完整流程：取对话历史 → 跑 ReAct 循环 → 问答入记忆 → 轨迹落库。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentExecutor agentExecutor;
    private final ConversationService conversationService;
    private final TraceService traceService;

    public ChatController(AgentExecutor agentExecutor,
                          ConversationService conversationService,
                          TraceService traceService) {
        this.agentExecutor = agentExecutor;
        this.conversationService = conversationService;
        this.traceService = traceService;
    }

    /** conversationId 为空则开新对话，响应带回，追问时携带以延续上下文 */
    public record ChatRequest(String question, String conversationId) {
    }

    public record ChatResponse(String conversationId, Long traceId, boolean success,
                               String answer, String sql, String queryResult, List<AgentStep> steps) {
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String question = request.question().strip();

        String conversationId = conversationService.getOrCreate(request.conversationId(), question);
        List<Message> history = conversationService.loadHistory(conversationId);

        long start = System.currentTimeMillis();
        AgentResult result = agentExecutor.run(question, history);
        long durationMs = System.currentTimeMillis() - start;

        conversationService.appendTurn(conversationId, question, result.answer());
        Long traceId = traceService.save(conversationId, question, result, durationMs);

        return new ChatResponse(conversationId, traceId, result.success(),
                result.answer(), result.sql(), result.queryResult(), result.steps());
    }
}
