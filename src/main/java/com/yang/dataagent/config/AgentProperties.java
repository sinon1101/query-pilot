package com.yang.dataagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 行为配置，对应 application.yml 的 agent.* 段。
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(int maxRounds, Sql sql, BizDatasource bizDatasource) {

    public record Sql(int maxRetries, int timeoutSeconds, int maxRows) {
    }

    public record BizDatasource(String url, String username, String password) {
    }
}
