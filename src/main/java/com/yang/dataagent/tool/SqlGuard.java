package com.yang.dataagent.tool;

import java.util.regex.Pattern;

/**
 * SQL 只读校验（第一层防线，第二层是数据库层的只读账号权限）。
 * <p>
 * 策略是白名单 + 黑名单双重收紧：语句必须以 SELECT/WITH 开头，
 * 且不得出现任何写操作 / DDL / 危险函数关键字。字符串字面量里撞上
 * 黑名单词会被误杀，属于可接受的代价——错误信息会回传给模型改写。
 */
public final class SqlGuard {

    /** 写操作、DDL、权限、执行控制、文件读写、DoS 函数，一律拒绝 */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|rename|replace"
                    + "|grant|revoke|call|handler|prepare|execute|deallocate"
                    + "|lock|unlock|set|use|kill|shutdown|load"
                    + "|into|outfile|dumpfile|load_file"
                    + "|sleep|benchmark|get_lock)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ALLOWED_START = Pattern.compile(
            "^(select|with)\\b", Pattern.CASE_INSENSITIVE);

    private SqlGuard() {
    }

    /**
     * @throws IllegalArgumentException 校验不通过，异常消息面向模型（会作为工具错误回传）
     */
    public static void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String trimmed = sql.strip();
        // 允许结尾一个分号，去掉后不得再出现分号（禁止多语句）
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException("只允许单条语句，不能包含分号分隔的多条 SQL");
        }
        if (trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("#")) {
            throw new IllegalArgumentException("SQL 中不允许出现注释（--、/*、#），请去掉注释后重试");
        }
        if (!ALLOWED_START.matcher(trimmed).find()) {
            throw new IllegalArgumentException("只允许 SELECT 或 WITH 开头的只读查询");
        }
        var m = FORBIDDEN.matcher(trimmed);
        if (m.find()) {
            throw new IllegalArgumentException("检测到禁用关键字 \"" + m.group(1) + "\"，只允许只读查询，请改写 SQL");
        }
    }
}
