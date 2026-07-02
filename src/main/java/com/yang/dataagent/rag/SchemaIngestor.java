package com.yang.dataagent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把 schema 知识文档向量化写入 Redis。
 * 文档 id 固定，重复启动按 id 覆盖（幂等）；文档量小（个位数），
 * 每次启动全量重灌的 embedding 开销可忽略，换来知识永远与代码同步。
 */
@Component
public class SchemaIngestor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaIngestor.class);

    private final RedisVectorStore vectorStore;

    public SchemaIngestor(RedisVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Document> docs = SchemaKnowledge.documents();
        long start = System.currentTimeMillis();
        vectorStore.add(docs);
        log.info("schema 知识库灌库完成: {} 个文档, 耗时 {}ms", docs.size(), System.currentTimeMillis() - start);
    }
}
