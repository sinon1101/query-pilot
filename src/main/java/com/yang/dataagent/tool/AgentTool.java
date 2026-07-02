package com.yang.dataagent.tool;

/**
 * Agent 工具的统一抽象。由 AgentExecutor 手动解析 tool call 并分发调用，
 * 不走 Spring AI 的自动工具执行（这是本项目刻意的设计选择）。
 */
public interface AgentTool {

    String name();

    String description();

    /** 参数的 JSON Schema，声明给模型用 */
    String inputSchema();

    /**
     * 执行工具。实现内部必须捕获业务异常并转为 error 输出，
     * 错误原文会回传给模型触发自我修正。
     */
    ToolOutput execute(String argumentsJson);

    record ToolOutput(String content, boolean error) {

        public static ToolOutput ok(String content) {
            return new ToolOutput(content, false);
        }

        public static ToolOutput fail(String message) {
            return new ToolOutput(message, true);
        }
    }
}
