package com.yang.dataagent.web;

import com.yang.dataagent.agent.AgentExecutor;
import com.yang.dataagent.agent.AgentResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第一阶段的最小接口：同步 REST。第三阶段升级为 SSE 流式输出。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentExecutor agentExecutor;

    public ChatController(AgentExecutor agentExecutor) {
        this.agentExecutor = agentExecutor;
    }

    public record ChatRequest(String question) {
    }

    @PostMapping("/chat")
    public AgentResult chat(@RequestBody ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        return agentExecutor.run(request.question().strip());
    }
}
