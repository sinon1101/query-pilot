package com.yang.dataagent.rag;

import com.yang.dataagent.config.AgentProperties;
import com.yang.dataagent.rag.SchemaKnowledge.SchemaDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合 schema 检索：稠密向量（语义）+ 词法（字面）双路召回，RRF 融合。
 * <p>
 * 单纯稠密向量对"顺丰""alipay""gmt_create"这类字面实体/字段名召回不稳（近义漂移）；
 * 单纯词法又抓不到"快递公司"→承运商这种语义等价。两路互补：
 * <ul>
 *   <li>稠密：{@link RedisVectorStore} KNN，text-embedding-v4。</li>
 *   <li>词法：内存倒排，把文本切成 <b>CJK 字符 bigram + ASCII token</b>（无需中文分词器），
 *       用 IDF 加权求和打分——字面命中的稀有 token（顺丰/手机数码/核销）得分高。</li>
 *   <li>融合：Reciprocal Rank Fusion，score = Σ 1/(k+rank)，k=60，只用名次不用异构分数，稳。</li>
 * </ul>
 * 文档语料是启动即固定的 schema 知识（百级），常驻内存做词法扫描成本可忽略。
 */
@Component
public class HybridSchemaRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridSchemaRetriever.class);
    private static final int RRF_K = 60;

    private final RedisVectorStore vectorStore;
    private final AgentProperties props;

    /** 语料：id → 文档 */
    private final Map<String, SchemaDoc> corpus = new LinkedHashMap<>();
    /** 词法倒排的中间态：每篇文档的 term 集合 */
    private final Map<String, Set<String>> docTerms = new HashMap<>();
    /** term → 文档频次，用于 IDF */
    private final Map<String, Integer> df = new HashMap<>();

    public HybridSchemaRetriever(RedisVectorStore vectorStore, AgentProperties props) {
        this.vectorStore = vectorStore;
        this.props = props;
        for (SchemaDoc d : SchemaKnowledge.documents()) {
            corpus.put(d.id(), d);
            Set<String> terms = tokenize(d.text());
            docTerms.put(d.id(), terms);
            for (String t : terms) {
                df.merge(t, 1, Integer::sum);
            }
        }
        log.info("词法索引构建完成: {} 篇文档, {} 个 term", corpus.size(), df.size());
    }

    /** 融合检索，返回前 finalK 篇文档（已按融合分降序）。 */
    public List<SchemaDoc> retrieve(String query, int finalK) {
        int poolK = props.rag().denseK();
        List<String> dense = denseRank(query, poolK);
        List<String> lexical = lexicalRank(query, poolK);

        Map<String, Double> fused = new HashMap<>();
        addRrf(fused, dense);
        addRrf(fused, lexical);

        List<String> topIds = fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(finalK)
                .map(Map.Entry::getKey)
                .toList();

        log.info("schema 检索 query=\"{}\" dense={} lexical={} 融合Top{}={}",
                query, dense.stream().limit(5).toList(), lexical.stream().limit(5).toList(), finalK, topIds);

        return topIds.stream().map(corpus::get).filter(java.util.Objects::nonNull).toList();
    }

    // ---------- 稠密 ----------

    private List<String> denseRank(String query, int k) {
        try {
            List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(k).build());
            List<String> ids = new ArrayList<>(hits.size());
            for (Document d : hits) {
                if (corpus.containsKey(d.getId())) {
                    ids.add(d.getId());
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("稠密检索失败，降级为纯词法: {}", e.getMessage());
            return List.of();
        }
    }

    // ---------- 词法 ----------

    private List<String> lexicalRank(String query, int k) {
        Set<String> qTerms = tokenize(query);
        int n = corpus.size();
        Map<String, Double> scores = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : docTerms.entrySet()) {
            double s = 0;
            for (String qt : qTerms) {
                if (e.getValue().contains(qt)) {
                    s += Math.log((n + 1.0) / (df.getOrDefault(qt, 0) + 1.0)) + 1.0; // IDF
                }
            }
            if (s > 0) {
                scores.put(e.getKey(), s);
            }
        }
        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(k)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void addRrf(Map<String, Double> fused, List<String> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            fused.merge(ranked.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    /**
     * 分词：ASCII 连续字母数字切成 token（≥2 长度，覆盖 alipay/status/gmv/brand），
     * CJK 连续片段切成相邻字符 bigram（单字片段退化为 unigram）。大小写归一。
     */
    static Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        String s = text.toLowerCase();
        int i = 0, len = s.length();
        while (i < len) {
            char ch = s.charAt(i);
            if (isAsciiAlnum(ch)) {
                int j = i;
                while (j < len && isAsciiAlnum(s.charAt(j))) {
                    j++;
                }
                if (j - i >= 2) {
                    terms.add(s.substring(i, j));
                }
                i = j;
            } else if (isCjk(ch)) {
                int j = i;
                while (j < len && isCjk(s.charAt(j))) {
                    j++;
                }
                if (j - i == 1) {
                    terms.add(s.substring(i, j));
                } else {
                    for (int p = i; p < j - 1; p++) {
                        terms.add(s.substring(p, p + 2));
                    }
                }
                i = j;
            } else {
                i++;
            }
        }
        return terms;
    }

    private static boolean isAsciiAlnum(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
    }

    private static boolean isCjk(char ch) {
        return ch >= '一' && ch <= '鿿';
    }
}
