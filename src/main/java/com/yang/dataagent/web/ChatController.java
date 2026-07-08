package com.yang.dataagent.web;

import com.yang.dataagent.agent.AgentEventListener;
import com.yang.dataagent.agent.AgentExecutor;
import com.yang.dataagent.agent.AgentResult;
import com.yang.dataagent.agent.AgentStep;
import com.yang.dataagent.memory.ConversationService;
import com.yang.dataagent.trace.TraceService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话接口。编排一轮问答的完整流程：取对话历史 → 跑 ReAct 循环 → 问答入记忆 → 轨迹落库。
 * <p>
 * 两个入口共用同一编排逻辑：
 * <ul>
 * <li>POST /api/chat        —— 同步 JSON，方便 curl / 脚本调用（评测集用）</li>
 * <li>POST /api/chat/stream —— SSE 流式，前端页面用。事件类型：
 *     meta（对话 id）→ delta（文本增量）*n → tool（工具开始执行）/ step（步骤定稿）*n
 *     → done（最终结果）；异常时发 error 事件</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** SSE 超时：Agent 多轮推理 + SQL 重试最坏情况也应在 5 分钟内结束 */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final AgentExecutor agentExecutor;
    private final ConversationService conversationService;
    private final TraceService traceService;

    /** ReAct 循环全程阻塞（模型流式消费 + JDBC），一个请求占一个虚拟线程正合适 */
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(AgentExecutor agentExecutor,
                          ConversationService conversationService,
                          TraceService traceService) {
        this.agentExecutor = agentExecutor;
        this.conversationService = conversationService;
        this.traceService = traceService;
    }

    /**
     * @param conversationId 为空则开新对话，响应带回，追问时携带以延续上下文
     * @param image          可选的图片输入（多模态）：data URI（data:image/png;base64,...）或纯 base64，
     *                       用于"读看板/表格截图"等场景；为空则纯文本问答
     */
    public record ChatRequest(String question, String conversationId, String image) {
    }

    public record ChatResponse(String conversationId, Long traceId, boolean success,
                               String answer, String sql, String queryResult, String chartOption,
                               List<AgentStep> steps) {
    }

    /** done 事件的载荷。steps 已通过 step 事件逐条推送，这里不再重复 */
    public record StreamDone(String conversationId, Long traceId, boolean success,
                             String answer, String sql, String queryResult, String chartOption) {
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String question = validateQuestion(request);

        String conversationId = conversationService.getOrCreate(request.conversationId(), question);
        List<Message> history = conversationService.loadHistory(conversationId);
        List<Media> media = buildMedia(request.image());

        long start = System.currentTimeMillis();
        AgentResult result = agentExecutor.run(question, history, AgentEventListener.NOOP, media);
        long durationMs = System.currentTimeMillis() - start;

        conversationService.appendTurn(conversationId, question, result.answer());
        Long traceId = traceService.save(conversationId, question, result, durationMs);

        return new ChatResponse(conversationId, traceId, result.success(),
                result.answer(), result.sql(), result.queryResult(), result.chartOption(), result.steps());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        String question = validateQuestion(request);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        sseExecutor.execute(() -> {
            try {
                String conversationId = conversationService.getOrCreate(request.conversationId(), question);
                send(emitter, "meta", Map.of("conversationId", conversationId));

                List<Message> history = conversationService.loadHistory(conversationId);
                List<Media> media = buildMedia(request.image());
                long start = System.currentTimeMillis();
                AgentResult result = agentExecutor.run(question, history, sseListener(emitter), media);
                long durationMs = System.currentTimeMillis() - start;

                // 客户端中途断开也照常落库：记忆与轨迹的完整性不依赖推送成功
                conversationService.appendTurn(conversationId, question, result.answer());
                Long traceId = traceService.save(conversationId, question, result, durationMs);

                send(emitter, "done", new StreamDone(conversationId, traceId, result.success(),
                        result.answer(), result.sql(), result.queryResult(), result.chartOption()));
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE 对话处理失败", e);
                send(emitter, "error", Map.of("message", String.valueOf(e.getMessage())));
                emitter.complete();
            }
        });
        return emitter;
    }

    private AgentEventListener sseListener(SseEmitter emitter) {
        return new AgentEventListener() {
            @Override
            public void onTextDelta(int round, String delta) {
                send(emitter, "delta", Map.of("round", round, "text", delta));
            }

            @Override
            public void onToolCallStart(int round, String toolName, String argumentsJson) {
                send(emitter, "tool", Map.of("round", round, "name", toolName, "input", argumentsJson));
            }

            @Override
            public void onStep(AgentStep step) {
                send(emitter, "step", step);
            }
        };
    }

    /**
     * 推送失败（客户端断开等）只记日志不抛出：回调在 Agent 执行线程上触发，
     * 抛出会把整个 ReAct 循环打断，导致本轮问答既不完整也不落库。
     */
    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 推送失败（客户端可能已断开）: event={} cause={}", event, e.getMessage());
        }
    }

    private String validateQuestion(ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        return request.question().strip();
    }

    /**
     * 把请求里的图片字段解析成 Spring AI 的 {@link Media}：接受 data URI
     * （data:image/png;base64,...，自带 MIME）或纯 base64（默认按 image/png 处理）。
     * 解析失败直接抛出，让上层按 400/500 返回，不静默吞掉。
     */
    private List<Media> buildMedia(String image) {
        if (image == null || image.isBlank()) {
            return List.of();
        }
        String data = image.trim();
        String mime = "image/png";
        if (data.startsWith("data:")) {
            int comma = data.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("非法的图片 data URI");
            }
            String header = data.substring("data:".length(), comma); // e.g. image/png;base64
            int semi = header.indexOf(';');
            mime = semi > 0 ? header.substring(0, semi) : header;
            data = data.substring(comma + 1);
        }
        byte[] bytes = Base64.getDecoder().decode(data);
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(mime))
                .data(new ByteArrayResource(bytes))
                .build();
        return List.of(media);
    }

    @PreDestroy
    void shutdown() {
        sseExecutor.shutdownNow();
    }
}
