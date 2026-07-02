package com.yang.dataagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yang.dataagent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索工具：按自然语言问题从 Redis 向量库检索最相关的表 DDL 和业务口径，
 * 返回给模型作为写 SQL 的依据。系统提示词不再内联全量表结构，
 * 表数量增长时上下文成本不随之膨胀，且模型不易臆造字段。
 */
@Component
@Order(1) // 注册顺序排在 execute_sql 前，与提示词中"先检索后查询"的流程一致
public class SchemaSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SchemaSearchTool.class);

    private final RedisVectorStore vectorStore;
    private final AgentProperties props;
    private final ObjectMapper objectMapper;

    public SchemaSearchTool(RedisVectorStore vectorStore, AgentProperties props, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "schema_search";
    }

    @Override
    public String description() {
        return "根据自然语言问题检索最相关的数据库表结构（DDL）和业务口径说明。"
                + "写 SQL 之前必须先调用本工具，严禁凭空猜测表名和字段名。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{"query":{"type":"string",
                "description":"检索用的自然语言描述，包含业务对象和指标，例如：品类销售额排行、用户城市分布"}},
                "required":["query"]}""";
    }

    @Override
    public ToolOutput execute(String argumentsJson) {
        String query;
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            query = args.path("query").asText(null);
        } catch (Exception e) {
            return ToolOutput.fail("工具参数不是合法 JSON: " + e.getMessage());
        }
        if (query == null || query.isBlank()) {
            return ToolOutput.fail("query 不能为空");
        }

        List<Document> docs;
        try {
            docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(props.rag().topK())
                    .build());
        } catch (Exception e) {
            log.warn("schema 检索失败: {}", e.getMessage());
            return ToolOutput.fail("schema 检索失败: " + e.getMessage());
        }

        if (docs.isEmpty()) {
            return ToolOutput.fail("未检索到相关表结构，请换个说法描述业务对象（如：订单、商品、用户）");
        }
        log.info("schema_search: query=\"{}\" 命中 {}", query,
                docs.stream().map(Document::getId).toList());
        return ToolOutput.ok(docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n")));
    }
}
