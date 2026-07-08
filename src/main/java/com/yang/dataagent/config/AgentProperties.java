package com.yang.dataagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 行为配置，对应 application.yml 的 agent.* 段。
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(int maxRounds, Sql sql, BizDatasource bizDatasource, Rag rag, Memory memory,
                              Reflect reflect, Router router) {

    public record Sql(int maxRetries, int timeoutSeconds, int maxRows) {
    }

    /**
     * 自适应复杂度路由配置。进 ReAct 循环前按问题分档（闲聊 / 简单 / 复杂），
     * 按档位动态决定：是否走工具、最大轮数、是否开 Critic 反思——把 phase-5
     * "反思该不该开"的全局两难变成按题决策。
     *
     * @param enabled          是否启用路由；false 时退化回原行为（一律 COMPLEX 档 + 全局 reflect），
     *                         用于 A/B 对照"路由 ON vs OFF"
     * @param simpleMaxRounds  SIMPLE 档最大轮数（简单计数/明细/模糊维度题，更短更省）
     * @param complexMaxRounds COMPLEX 档最大轮数（口径敏感题，留足自愈+反思空间）
     */
    public record Router(boolean enabled, int simpleMaxRounds, int complexMaxRounds) {
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
