package com.yang.dataagent.agent;

/**
 * ReAct 循环的实时事件回调，供 SSE 流式输出订阅。
 * 同步接口：回调在 Agent 执行线程上触发，实现必须快速返回且不抛异常
 * （抛出会中断整个循环）。
 */
public interface AgentEventListener {

    AgentEventListener NOOP = new AgentEventListener() {
    };

    /** 模型文本增量（打字机效果的数据源）。最后一轮的增量拼起来就是最终回答 */
    default void onTextDelta(int round, String delta) {
    }

    /** 工具即将执行（工具可能耗时数秒，前端据此显示"正在执行"状态） */
    default void onToolCallStart(int round, String toolName, String argumentsJson) {
    }

    /** 一个完整步骤结束（思考文本定稿 / 工具执行完毕） */
    default void onStep(AgentStep step) {
    }
}
