package com.yang.dataagent.agent;

/**
 * 执行轨迹中的一步：模型的思考文本，或一次工具调用。
 * 第一阶段随响应返回，第二阶段落库支持回放。
 *
 * @param round  ReAct 第几轮（从 1 开始）
 * @param type   thought（模型文本）/ tool_call（工具调用）/ guardrail（执行器注入的规则提醒）/
 *               reflection（Critic 语义审校结论）
 * @param name   工具名，thought 时为 null
 * @param input  工具入参 JSON，thought 时为 null
 * @param output 工具输出或模型文本
 * @param error  该步是否出错（工具执行失败）
 */
public record AgentStep(int round, String type, String name, String input, String output, boolean error) {

    public static AgentStep thought(int round, String text) {
        return new AgentStep(round, "thought", null, null, text, false);
    }

    public static AgentStep toolCall(int round, String name, String input, String output, boolean error) {
        return new AgentStep(round, "tool_call", name, input, output, error);
    }

    public static AgentStep guardrail(int round, String text) {
        return new AgentStep(round, "guardrail", null, null, text, false);
    }

    /** Critic 语义审校步骤：pass=true 通过，false 打回重做（output 记审校结论） */
    public static AgentStep reflection(int round, String text) {
        return new AgentStep(round, "reflection", null, null, text, false);
    }
}
