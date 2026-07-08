package com.yang.dataagent.agent;

/**
 * 路由决策：{@link QuestionRouter} 对一个问题分档后产出的执行策略，
 * 供 {@link AgentExecutor} 按档位跑这一轮。
 *
 * @param tier           复杂度档位
 * @param usesTools      是否进工具循环（CHITCHAT 为 false，一次 LLM 直接答）
 * @param reflectEnabled 收敛前是否开 Critic 语义审校（只有 COMPLEX 档开）
 * @param maxRounds      本轮 ReAct 最大轮数（CHITCHAT 不适用，取 0）
 * @param reason         判定理由（命中的词/信号），落 trace 支持"为什么这么路由"的回放
 */
public record RouteDecision(Tier tier, boolean usesTools, boolean reflectEnabled, int maxRounds, String reason) {

    /** trace 里 route 步骤的展示文本 */
    public String describe() {
        return "复杂度分档：" + tier
                + "（走工具=" + usesTools + "，反思=" + reflectEnabled + "，最大轮数=" + maxRounds + "）\n"
                + "依据：" + reason;
    }
}
