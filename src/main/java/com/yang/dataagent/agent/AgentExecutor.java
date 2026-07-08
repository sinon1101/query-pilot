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
import org.springframework.ai.content.Media;
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
 * <p>
 * 每轮模型调用走流式（{@link #streamOneRound}）：文本增量实时回调给
 * {@link AgentEventListener}（SSE 打字机效果的数据源），tool call 分片手写聚合。
 */
@Service
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final AgentProperties props;
    private final ObjectMapper objectMapper;
    private final Critic critic;

    public AgentExecutor(ChatModel chatModel, ToolRegistry toolRegistry,
                         AgentProperties props, ObjectMapper objectMapper, Critic critic) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.props = props;
        this.objectMapper = objectMapper;
        this.critic = critic;
    }

    public AgentResult run(String question) {
        return run(question, List.of());
    }

    public AgentResult run(String question, List<Message> history) {
        return run(question, history, AgentEventListener.NOOP);
    }

    public AgentResult run(String question, List<Message> history, AgentEventListener listener) {
        return run(question, history, listener, List.of());
    }

    /**
     * @param history  已截断的历史对话（user/assistant 成对），注入在系统提示词之后、
     *                 本轮问题之前，供模型理解"那第二名呢"这类指代追问
     * @param listener 实时事件回调（文本增量 / 工具执行），SSE 流式输出的数据源
     * @param media    本轮问题附带的图片（多模态：看板/表格截图等），空则退化为纯文本问答。
     *                 图片只挂在首轮 user 消息上，后续工具轮沿用同一消息列表，模型全程可见。
     */
    public AgentResult run(String question, List<Message> history, AgentEventListener listener, List<Media> media) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(AgentPrompts.SYSTEM_PROMPT));
        messages.addAll(history);
        messages.add(buildUserMessage(question, media));

        // 关键：internalToolExecutionEnabled(false) 让框架只把 tool call 透传出来，由本循环手动执行
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolRegistry.toolCallbacks())
                .internalToolExecutionEnabled(false)
                .build();

        List<AgentStep> steps = new ArrayList<>();
        String lastSql = null;
        String lastQueryResult = null;
        String lastChartOption = null;
        int consecutiveSqlFailures = 0;
        boolean chartReminderSent = false;
        int reflectionsUsed = 0;

        for (int round = 1; round <= props.maxRounds(); round++) {
            log.debug("ReAct 第 {} 轮，消息数 {}", round, messages.size());
            AssistantMessage assistant = streamOneRound(messages, options, round, listener);

            // 模型不再调用工具 => 收敛，文本即最终回答。
            // 最终回答不记为 thought 步骤：它已存在 AgentResult.answer / trace.answer，
            // 记了会在轨迹里重复一遍，前端也会闪烁（先挪进轨迹又被 done 恢复）
            if (!assistant.hasToolCalls()) {
                // 出图兜底：结果明显可视化却没出图时，打断收敛注入一次提醒（详见 CHART_REMINDER）
                if (!chartReminderSent && lastChartOption == null && looksChartable(lastQueryResult)) {
                    chartReminderSent = true;
                    log.info("查询结果适合可视化但未出图，注入出图提醒");
                    // 被打断的收敛文本降级为思考步骤，避免在前端/轨迹中丢失
                    if (assistant.getText() != null && !assistant.getText().isBlank()) {
                        AgentStep thought = AgentStep.thought(round, assistant.getText());
                        steps.add(thought);
                        listener.onStep(thought);
                    }
                    AgentStep guardrail = AgentStep.guardrail(round, AgentPrompts.CHART_REMINDER);
                    steps.add(guardrail);
                    listener.onStep(guardrail);
                    messages.add(assistant);
                    messages.add(new UserMessage(AgentPrompts.CHART_REMINDER));
                    continue;
                }

                // 语义审校（Reflexion）：SQL 能跑通不代表口径对。收敛前让独立 Critic 审一遍，
                // 判 revise 就打回让主循环重做，限次数防止拉锯。只在真跑过 SQL 时审。
                if (props.reflect().enabled() && reflectionsUsed < props.reflect().maxReflections()
                        && lastSql != null && lastQueryResult != null) {
                    reflectionsUsed++;
                    Critic.Critique critique = critic.review(question, lastSql, lastQueryResult, assistant.getText());
                    if (!critique.pass()) {
                        log.info("Critic 判 revise，打回重做: {}", critique.issue());
                        // 草稿结论降级为思考步骤，避免在轨迹/前端丢失（前端据此清空正文区待重写）
                        if (assistant.getText() != null && !assistant.getText().isBlank()) {
                            AgentStep draft = AgentStep.thought(round, assistant.getText());
                            steps.add(draft);
                            listener.onStep(draft);
                        }
                        AgentStep reflection = AgentStep.reflection(round, "审校打回：" + critique.issue());
                        steps.add(reflection);
                        listener.onStep(reflection);
                        messages.add(assistant);
                        messages.add(new UserMessage(
                                AgentPrompts.REFLECTION_REVISE_TEMPLATE.replace("{issue}", critique.issue())));
                        continue;
                    }
                    AgentStep reflection = AgentStep.reflection(round, "审校通过");
                    steps.add(reflection);
                    listener.onStep(reflection);
                }
                return new AgentResult(true, assistant.getText(), lastSql, lastQueryResult, lastChartOption, steps);
            }

            if (assistant.getText() != null && !assistant.getText().isBlank()) {
                AgentStep thought = AgentStep.thought(round, assistant.getText());
                steps.add(thought);
                listener.onStep(thought);
            }

            messages.add(assistant);
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                log.info("第 {} 轮工具调用: {} args={}", round, toolCall.name(), toolCall.arguments());
                listener.onToolCallStart(round, toolCall.name(), toolCall.arguments());
                AgentTool.ToolOutput output = toolRegistry.dispatch(toolCall.name(), toolCall.arguments());
                AgentStep step = AgentStep.toolCall(round, toolCall.name(), toolCall.arguments(),
                        output.content(), output.error());
                steps.add(step);
                listener.onStep(step);
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
                } else if ("render_chart".equals(toolCall.name()) && !output.error()) {
                    lastChartOption = output.content();
                }
            }
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());

            if (consecutiveSqlFailures >= props.sql().maxRetries()) {
                log.warn("execute_sql 连续失败 {} 次，终止", consecutiveSqlFailures);
                return new AgentResult(false,
                        "查询失败：SQL 连续 " + consecutiveSqlFailures + " 次执行出错，已停止重试。请换个问法或检查数据口径。",
                        lastSql, lastQueryResult, lastChartOption, steps);
            }
        }

        log.warn("达到最大轮数 {} 仍未收敛", props.maxRounds());
        return new AgentResult(false,
                "本次分析超过最大推理轮数（" + props.maxRounds() + "）仍未得出结论，已终止。请把问题拆小后重试。",
                lastSql, lastQueryResult, lastChartOption, steps);
    }

    /** 带图片则构建多模态 UserMessage（文本 + Media），否则退化为纯文本 */
    private static UserMessage buildUserMessage(String question, List<Media> media) {
        if (media == null || media.isEmpty()) {
            return new UserMessage(question);
        }
        return UserMessage.builder().text(question).media(media).build();
    }

    /** tool call 聚合的中间态：arguments 可能分片到达，用 StringBuilder 续拼 */
    private record ToolCallDraft(String id, String name, StringBuilder arguments) {
    }

    /**
     * 流式消费一轮模型响应，聚合成完整的 AssistantMessage：
     * 文本增量实时回调 listener 后拼接；tool call 按分片聚合——带 id 的分片开启
     * 一个新调用，无 id 的分片把 arguments 续到上一个调用（OpenAI 风格增量协议）。
     * 实测 DashScope 当前把 tool call 完整放在最后一个 chunk（退化为单分片），
     * 但聚合逻辑按通用增量协议实现，两种形状都兼容。
     */
    private AssistantMessage streamOneRound(List<Message> messages, ToolCallingChatOptions options,
                                            int round, AgentEventListener listener) {
        StringBuilder text = new StringBuilder();
        List<ToolCallDraft> drafts = new ArrayList<>();

        // toIterable 阻塞消费 Flux：增量到达即处理，无需引入响应式编程模型
        for (ChatResponse chunk : chatModel.stream(new Prompt(messages, options)).toIterable()) {
            if (chunk.getResults().isEmpty() || chunk.getResult().getOutput() == null) {
                continue;
            }
            AssistantMessage out = chunk.getResult().getOutput();
            String delta = out.getText();
            if (delta != null && !delta.isEmpty()) {
                text.append(delta);
                listener.onTextDelta(round, delta);
            }
            for (AssistantMessage.ToolCall tc : out.getToolCalls()) {
                if (tc.id() != null && !tc.id().isBlank()) {
                    drafts.add(new ToolCallDraft(tc.id(), tc.name(),
                            new StringBuilder(nullToEmpty(tc.arguments()))));
                } else if (!drafts.isEmpty()) {
                    drafts.getLast().arguments().append(nullToEmpty(tc.arguments()));
                } else {
                    log.warn("收到无 id 且无前置调用的 tool call 分片，丢弃: name={} args={}",
                            tc.name(), tc.arguments());
                }
            }
        }

        // DashScope 偶发丢 arguments 尾部分片，损坏的 JSON 回传会被 400 拒绝并炸掉整个循环，
        // 必须先修复（缺尾补括号；修不好降级 "{}" 走工具报错自愈）
        List<AssistantMessage.ToolCall> toolCalls = drafts.stream()
                .map(d -> {
                    String args = ToolCallJsonRepair.repair(d.arguments().toString(), objectMapper);
                    if (!args.equals(d.arguments().toString())) {
                        log.warn("tool call 参数非法 JSON，已修复: name={} 原文={} 修复后={}",
                                d.name(), d.arguments(), args);
                    }
                    return new AssistantMessage.ToolCall(d.id(), "function", d.name(), args);
                })
                .toList();
        return AssistantMessage.builder().content(text.toString()).toolCalls(toolCalls).build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 查询结果是否"明显适合出图"：2~50 行，且每行同时有数值列和非数值的标签列
     * （典型的分类/时间 + 指标形状）。只做粗判，最终是否出图仍由模型决定。
     */
    private boolean looksChartable(String queryResultJson) {
        if (queryResultJson == null) {
            return false;
        }
        try {
            var root = objectMapper.readTree(queryResultJson);
            var rows = root.path("rows");
            int rowCount = root.path("rowCount").asInt(0);
            if (rowCount < 2 || rowCount > 50 || !rows.isArray() || rows.isEmpty()) {
                return false;
            }
            var first = rows.get(0);
            boolean hasNumeric = false;
            boolean hasLabel = false;
            var fields = first.fields();
            while (fields.hasNext()) {
                String v = fields.next().getValue().asText("");
                if (v.matches("-?\\d+(\\.\\d+)?")) {
                    hasNumeric = true;
                } else if (!v.isBlank()) {
                    hasLabel = true;
                }
            }
            return hasNumeric && hasLabel;
        } catch (Exception e) {
            return false;
        }
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
