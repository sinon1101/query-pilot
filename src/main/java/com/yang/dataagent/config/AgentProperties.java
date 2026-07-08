package com.yang.dataagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 行为配置，对应 application.yml 的 agent.* 段。
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(int maxRounds, Sql sql, BizDatasource bizDatasource, Rag rag, Memory memory,
                              Reflect reflect) {

    public record Sql(int maxRetries, int timeoutSeconds, int maxRows) {
    }

    /**
     * 反思/Critic 语义审校配置。
     *
     * @param enabled        是否启用收敛前的语义审校
     * @param maxReflections 单次对话最多打回重做几次（防止 Critic ↔ 修正 反复拉锯烧 token）
     */
    public record Reflect(boolean enabled, int maxReflections) {
    }

    public record BizDatasource(String url, String username, String password) {
    }

    /**
     * @param topK   schema_search 最终融合返回的文档数
     * @param denseK 稠密/词法各自的候选池大小（RRF 融合前每路取多少），应 &gt;= topK
     */
    public record Rag(String redisHost, int redisPort, int topK, int denseK) {
    }

    public record Memory(int windowSize) {
    }
}
