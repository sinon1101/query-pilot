package com.yang.dataagent.agent;

/**
 * 问题复杂度档位。由 {@link QuestionRouter} 在进 ReAct 循环前判定，
 * 决定这一轮的执行策略（是否走工具 / 最大轮数 / 是否开反思）。
 * <ul>
 *   <li>{@link #CHITCHAT} —— 问候、元问题（"你能做什么"）等与数据无关的闲聊，
 *       不进工具循环、不检索、不反思，一次 LLM 直接答（并把话题引回数据分析）。</li>
 *   <li>{@link #SIMPLE} —— 简单计数/明细，以及"城市""周末"这类**模糊维度题**：
 *       走工具但轮数收紧、**不反思**。模糊题不反思是刻意的——phase-5 实测 Critic 对
 *       这类一词多解问题过度纠偏，把 Agent 拖进反复重查直到超轮数。</li>
 *   <li>{@link #COMPLEX} —— 命中硬口径词（销售额/GMV/动销率/退款率/核销率/妥投率/客单价…）的
 *       口径敏感题：留足轮数并开 Critic 反思，正是反思的价值区。</li>
 * </ul>
 */
public enum Tier {
    CHITCHAT,
    SIMPLE,
    COMPLEX
}
