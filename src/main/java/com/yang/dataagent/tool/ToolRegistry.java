package com.yang.dataagent.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：把 AgentTool 的定义暴露给模型（仅声明），执行则由
 * AgentExecutor 显式分发到 {@link AgentTool#execute}。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> toolList) {
        toolList.forEach(t -> tools.put(t.name(), t));
    }

    /** 声明给模型的工具定义。call() 永远不该被框架触发，触发即是配置错误。 */
    public List<ToolCallback> toolCallbacks() {
        return tools.values().stream().map(this::definitionOnly).toList();
    }

    public AgentTool.ToolOutput dispatch(String name, String argumentsJson) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return AgentTool.ToolOutput.fail("未知工具: " + name + "，可用工具: " + tools.keySet());
        }
        return tool.execute(argumentsJson);
    }

    private ToolCallback definitionOnly(AgentTool tool) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .inputSchema(tool.inputSchema())
                        .build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException(
                        "工具由 AgentExecutor 手动分发，不应走框架自动执行: " + tool.name());
            }
        };
    }
}
