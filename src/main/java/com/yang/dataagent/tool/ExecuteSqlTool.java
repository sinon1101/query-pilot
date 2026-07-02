package com.yang.dataagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yang.dataagent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在只读业务库上执行 SELECT。防线从外到内：
 * 1. SqlGuard 语句级校验（白名单开头 + 黑名单关键字 + 禁多语句/注释）
 * 2. 连接使用 agent_ro 账号，数据库权限仅 biz 库 SELECT
 * 3. JdbcTemplate 层 10s 查询超时 + 最大行数截断
 * 执行报错时把数据库错误原文返回给模型，触发下一轮自我修正。
 */
@Component
public class ExecuteSqlTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ExecuteSqlTool.class);

    private final JdbcTemplate bizJdbcTemplate;
    private final AgentProperties props;
    private final ObjectMapper objectMapper;

    public ExecuteSqlTool(JdbcTemplate bizJdbcTemplate, AgentProperties props, ObjectMapper objectMapper) {
        this.bizJdbcTemplate = bizJdbcTemplate;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "execute_sql";
    }

    @Override
    public String description() {
        return "在只读的电商业务库（MySQL 8）上执行一条 SELECT 查询并返回结果。"
                + "只允许单条 SELECT/WITH 语句，禁止任何写操作。"
                + "结果最多返回 " + props.sql().maxRows() + " 行，聚合类问题请在 SQL 里完成聚合。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{"sql":{"type":"string",
                "description":"要执行的单条 SELECT 语句（MySQL 8 方言，不要带分号和注释）"}},
                "required":["sql"]}""";
    }

    @Override
    public ToolOutput execute(String argumentsJson) {
        String sql;
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            sql = args.path("sql").asText(null);
        } catch (Exception e) {
            return ToolOutput.fail("工具参数不是合法 JSON: " + e.getMessage());
        }

        try {
            SqlGuard.validate(sql);
        } catch (IllegalArgumentException e) {
            log.warn("SQL 校验拒绝: {} | sql={}", e.getMessage(), sql);
            return ToolOutput.fail("SQL 校验不通过: " + e.getMessage());
        }

        log.info("execute_sql: {}", sql);
        try {
            List<Map<String, Object>> rows = bizJdbcTemplate.queryForList(sql);
            return ToolOutput.ok(formatResult(rows));
        } catch (DataAccessException e) {
            // 报错原文回传给模型，这是自愈机制的输入
            String rootMessage = e.getMostSpecificCause().getMessage();
            log.warn("SQL 执行失败: {}", rootMessage);
            return ToolOutput.fail("SQL 执行失败: " + rootMessage);
        }
    }

    private String formatResult(List<Map<String, Object>> rows) throws DataAccessException {
        int maxRows = props.sql().maxRows();
        boolean truncated = rows.size() > maxRows;
        if (truncated) {
            rows = rows.subList(0, maxRows);
        }
        // 所有值转字符串，避免日期/BigDecimal 序列化出奇怪格式干扰模型
        List<Map<String, String>> normalized = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, String> r = new LinkedHashMap<>();
            row.forEach((k, v) -> r.put(k, v == null ? null : String.valueOf(v)));
            normalized.add(r);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", normalized.size());
        result.put("truncated", truncated);
        result.put("rows", normalized);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("查询结果序列化失败", e);
        }
    }
}
