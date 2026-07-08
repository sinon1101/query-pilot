package com.yang.dataagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 语义审校员（Critic / Reflexion）：收敛前对"问题 + SQL + 结果 + 草稿结论"做一次独立的
 * LLM 审校，判断结果在**业务语义**上是否真正回答了问题。
 * <p>
 * 它补的是现有"SQL 报错自愈"的盲区——报错自愈只能修**语法/字段不存在**这类会抛异常的错，
 * 而"SQL 能跑通但口径错了"（如分品类用了 total_amount、漏了有效订单过滤、status 张冠李戴）
 * 不会报错，结果照样是错的。Critic 就是抓这类静默的语义错，判 revise 则打回让主循环重做。
 * <p>
 * 独立于主循环的 ChatModel 调用，不绑工具、不流式；解析失败或调用异常一律 fail-open（放行），
 * 宁可漏判也不误伤正确结果、不阻断主流程。
 */
@Component
public class Critic {

    private static final Logger log = LoggerFactory.getLogger(Critic.class);
    private static final int RESULT_MAX_CHARS = 3000; // 查询结果注入审校的截断上限

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public Critic(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /** @param pass true=通过，false=打回；issue 为打回原因（通过时为 null） */
    public record Critique(boolean pass, String issue) {
    }

    public Critique review(String question, String sql, String resultJson, String draftAnswer) {
        String result = resultJson == null ? "(无查询结果)"
                : resultJson.length() > RESULT_MAX_CHARS ? resultJson.substring(0, RESULT_MAX_CHARS) + "…(已截断)" : resultJson;
        String user = "【原始问题】\n" + question
                + "\n\n【Agent 执行的 SQL】\n" + (sql == null ? "(无)" : sql)
                + "\n\n【查询结果】\n" + result
                + "\n\n【草稿结论】\n" + draftAnswer;
        try {
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(AgentPrompts.CRITIC_SYSTEM_PROMPT),
                    new UserMessage(user))));
            String text = response.getResult().getOutput().getText();
            return parse(text);
        } catch (Exception e) {
            log.warn("Critic 审校调用失败，放行: {}", e.getMessage());
            return new Critique(true, null);
        }
    }

    /** 宽松解析：剥 ```json 围栏、截取第一个 {...}；解析不出或非 revise 一律视为通过（fail-open） */
    private Critique parse(String text) {
        if (text == null || text.isBlank()) {
            return new Critique(true, null);
        }
        String json = text.trim();
        int lb = json.indexOf('{');
        int rb = json.lastIndexOf('}');
        if (lb < 0 || rb <= lb) {
            log.warn("Critic 输出非 JSON，放行: {}", text);
            return new Critique(true, null);
        }
        try {
            JsonNode node = objectMapper.readTree(json.substring(lb, rb + 1));
            String verdict = node.path("verdict").asText("pass").trim().toLowerCase();
            String issue = node.path("issue").asText("").trim();
            boolean revise = verdict.startsWith("revise") && !issue.isEmpty();
            return new Critique(!revise, revise ? issue : null);
        } catch (Exception e) {
            log.warn("Critic JSON 解析失败，放行: {}", text);
            return new Critique(true, null);
        }
    }
}
