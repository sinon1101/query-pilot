package com.yang.dataagent.agent;

import java.util.List;

/**
 * 一次 Agent 执行的最终结果。
 *
 * @param success     是否正常得出结论（false：超轮数 / SQL 连续失败达上限）
 * @param answer      面向用户的最终回答
 * @param sql         最后一次成功执行的 SQL（可能为 null，如闲聊类问题）
 * @param queryResult 该 SQL 的查询结果 JSON 字符串
 * @param chartOption 最后一次成功生成的 ECharts option JSON（未出图时为 null）
 * @param steps       完整执行轨迹
 */
public record AgentResult(boolean success, String answer, String sql, String queryResult,
                          String chartOption, List<AgentStep> steps) {
}
