package com.yang.dataagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yang.dataagent.config.AgentProperties;
import com.yang.dataagent.tool.AgentTool;
import com.yang.dataagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写 ReAct 循环——本项目的核心。刻意不用框架的自动工具执行，
 * 自己维护消息列表、解析并分发 tool call、控制轮数与错误自愈：
 * <pre>
 * 用户问题 → [模型推理 → 工具调用 → 结果回填]×N → 最终回答
 * </pre>
 * 终止条件（三选一）：模型不再发起工具调用（正常收敛）、
 * 达到最大轮数、execute_sql 连续失败达重试上限。
 */
@Service
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final AgentProperties props;
    private final ObjectMapper objectMapper;

    public AgentExecutor(ChatModel chatModel, ToolRegistry toolRegistry,
                         AgentProperties props, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public AgentResult run(String question) {
        return run(question, List.of());
    }

    /**
     * @param history 已截断的历史对话（user/assistant 成对），注入在系统提示词之后、
     *                本轮问题之前，供模型理解"那第二名呢"这类指代追问
     */
    public AgentResult run(String question, List<Message> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(AgentPrompts.SYSTEM_PROMPT));
        messages.addAll(history);
        messages.add(new UserMessage(question));

        // 关键：internalToolExecutionEnabled(false) 让框架只把 tool call 透传出来，由本循环手动执行
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolRegistry.toolCallbacks())
                .internalToolExecutionEnabled(false)
                .build();

        List<AgentStep> steps = new ArrayList<>();
        String lastSql = null;
        String lastQueryResult = null;
        int consecutiveSqlFailures = 0;

        for (int round = 1; round <= props.maxRounds(); round++) {
            log.debug("ReAct 第 {} 轮，消息数 {}", round, messages.size());
            ChatResponse response = chatModel.call(new Prompt(messages, options));
            AssistantMessage assistant = response.getResult().getOutput();

            if (assistant.getText() != null && !assistant.getText().isBlank()) {
                steps.add(AgentStep.thought(round, assistant.getText()));
            }

            // 模型不再调用工具 => 收敛，文本即最终回答
            if (!assistant.hasToolCalls()) {
                return new AgentResult(true, assistant.getText(), lastSql, lastQueryResult, steps);
            }

            messages.add(assistant);
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                log.info("第 {} 轮工具调用: {} args={}", round, toolCall.name(), toolCall.arguments());
                AgentTool.ToolOutput output = toolRegistry.dispatch(toolCall.name(), toolCall.arguments());
                steps.add(AgentStep.toolCall(round, toolCall.name(), toolCall.arguments(),
                        output.content(), output.error()));
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), output.content()));

                if ("execute_sql".equals(toolCall.name())) {
                    if (output.error()) {
                        consecutiveSqlFailures++;
                    } else {
                        consecutiveSqlFailures = 0;
                        lastSql = extractSql(toolCall.arguments());
                        lastQueryResult = output.content();
                    }
                }
            }
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());

            if (consecutiveSqlFailures >= props.sql().maxRetries()) {
                log.warn("execute_sql 连续失败 {} 次，终止", consecutiveSqlFailures);
                return new AgentResult(false,
                        "查询失败：SQL 连续 " + consecutiveSqlFailures + " 次执行出错，已停止重试。请换个问法或检查数据口径。",
                        lastSql, lastQueryResult, steps);
            }
        }

        log.warn("达到最大轮数 {} 仍未收敛", props.maxRounds());
        return new AgentResult(false,
                "本次分析超过最大推理轮数（" + props.maxRounds() + "）仍未得出结论，已终止。请把问题拆小后重试。",
                lastSql, lastQueryResult, steps);
    }

    /** 从工具入参 JSON 里提取 sql 字段，仅用于结果展示，解析失败不影响主流程 */
    private String extractSql(String argumentsJson) {
        try {
            return objectMapper.readTree(argumentsJson).path("sql").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
