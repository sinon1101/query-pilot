package com.yang.dataagent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

/**
 * Redis 向量库（RediSearch）手动装配。刻意不用 starter 自动配置：
 * 索引名、前缀、元数据字段都显式声明，行为可预期。
 * 向量化用百炼 text-embedding-v4（DashScope starter 自动装配的 EmbeddingModel）。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public JedisPooled jedisPooled(AgentProperties props) {
        return new JedisPooled(props.rag().redisHost(), props.rag().redisPort());
    }

    @Bean
    public RedisVectorStore vectorStore(JedisPooled jedisPooled, EmbeddingModel embeddingModel) {
        return RedisVectorStore.builder(jedisPooled, embeddingModel)
                .indexName("schema-knowledge-idx")
                .prefix("schema:")
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("type"),
                        RedisVectorStore.MetadataField.tag("name"),
                        RedisVectorStore.MetadataField.tag("table"))
                .initializeSchema(true)
                .build();
    }
}
