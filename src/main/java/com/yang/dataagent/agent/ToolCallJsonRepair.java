package com.yang.dataagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 流式 tool call 参数的括号平衡修复。
 * <p>
 * DashScope 流式返回偶发丢失 tool call 参数的尾部分片（实测约 1/20 概率，
 * 典型形态是缺结尾的 "}"）。损坏的 JSON 若原样回传给 DashScope 会被
 * InvalidParameter 400 拒绝，直接炸掉整个 ReAct 循环。这里做最小修复：
 * 扫描一遍补齐未闭合的引号与括号；修不好则降级为 "{}"——工具会以
 * "参数不是合法 JSON" 报错回传模型，走既有的自愈重试路径。
 */
final class ToolCallJsonRepair {

    private ToolCallJsonRepair() {
    }

    /** 返回可安全回传给模型 API 的合法 JSON：原文 / 补尾修复 / 兜底 "{}" */
    static String repair(String arguments, ObjectMapper objectMapper) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        if (isValidJson(arguments, objectMapper)) {
            return arguments;
        }
        String balanced = balance(arguments);
        if (isValidJson(balanced, objectMapper)) {
            return balanced;
        }
        return "{}";
    }

    private static boolean isValidJson(String s, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(s) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 补齐未闭合的字符串引号和 {} / []（忽略字符串内部的括号与转义引号） */
    private static String balance(String s) {
        Deque<Character> closers = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = inString;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            switch (c) {
                case '{' -> closers.push('}');
                case '[' -> closers.push(']');
                case '}', ']' -> {
                    if (!closers.isEmpty() && closers.peek() == c) {
                        closers.pop();
                    }
                }
                default -> { /* 其它字符不影响括号栈 */ }
            }
        }
        StringBuilder repaired = new StringBuilder(s);
        if (inString) {
            repaired.append('"');
        }
        while (!closers.isEmpty()) {
            repaired.append(closers.pop());
        }
        return repaired.toString();
    }
}
