package com.yang.dataagent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Set;

/**
 * 启动时把多粒度 schema 知识文档（表/列/取值/口径，约百级）向量化写入 Redis。
 * <p>
 * 先按前缀清空旧文档再全量重灌：文档 id 会随 schema 演进增删，只 upsert 会残留过期文档
 * 污染检索；语料是启动即定的静态知识，全量重灌换来"检索内容永远与代码同步"。
 * 分批调用 add，规避 embedding 接口单次批量上限。
 */
@Component
public class SchemaIngestor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaIngestor.class);
    private static final String KEY_PATTERN = "schema:*";
    private static final int BATCH = 10; // text-embedding-v4 单次 batch 上限为 10，超了 400 InvalidParameter

    private final RedisVectorStore vectorStore;
    private final JedisPooled jedis;

    public SchemaIngestor(RedisVectorStore vectorStore, JedisPooled jedis) {
        this.vectorStore = vectorStore;
        this.jedis = jedis;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> stale = jedis.keys(KEY_PATTERN);
        if (stale != null && !stale.isEmpty()) {
            jedis.del(stale.toArray(new String[0]));
            log.info("清理旧 schema 文档 {} 条", stale.size());
        }

        List<Document> docs = SchemaKnowledge.documents().stream()
                .map(SchemaKnowledge.SchemaDoc::toDocument)
                .toList();
        long start = System.currentTimeMillis();
        for (int i = 0; i < docs.size(); i += BATCH) {
            vectorStore.add(docs.subList(i, Math.min(i + BATCH, docs.size())));
        }
        log.info("schema 知识库灌库完成: {} 个文档, 耗时 {}ms", docs.size(), System.currentTimeMillis() - start);
    }
}
