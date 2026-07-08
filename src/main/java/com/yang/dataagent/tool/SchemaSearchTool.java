package com.yang.dataagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yang.dataagent.config.AgentProperties;
import com.yang.dataagent.rag.HybridSchemaRetriever;
import com.yang.dataagent.rag.SchemaKnowledge;
import com.yang.dataagent.rag.SchemaKnowledge.Col;
import com.yang.dataagent.rag.SchemaKnowledge.DimValue;
import com.yang.dataagent.rag.SchemaKnowledge.SchemaDoc;
import com.yang.dataagent.rag.SchemaKnowledge.Tbl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * schema linking 工具：给一句自然语言问题，把 19 张表的库收敛成"该用哪几张表、哪些字段、
 * 涉及哪些取值和口径"的结构化上下文，喂给模型写 SQL。相比第一版"按表检索 + 拼 DDL 原文"，
 * 这里做了三件事：
 * <ol>
 *   <li>混合检索（{@link HybridSchemaRetriever}：稠密向量 + 词法 RRF 融合）定位相关表/列/取值/口径；</li>
 *   <li>确定性实体链接：扫描问题中出现的已知枚举值（顺丰/手机数码/金卡…），直接锁定对应字段；</li>
 *   <li>结构化输出：命中的表渲染成完整列清单（含类型/注释/外键），另附取值映射与业务口径，
 *       而不是把整库 DDL 一股脑塞给模型。</li>
 * </ol>
 */
@Component
@Order(1) // 注册顺序排在 execute_sql 前，与提示词"先检索后查询"一致
public class SchemaSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SchemaSearchTool.class);
    private static final int MAX_TABLES = 6; // 渲染的相关表数上限，控上下文

    private final HybridSchemaRetriever retriever;
    private final AgentProperties props;
    private final ObjectMapper objectMapper;

    public SchemaSearchTool(HybridSchemaRetriever retriever, AgentProperties props, ObjectMapper objectMapper) {
        this.retriever = retriever;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "schema_search";
    }

    @Override
    public String description() {
        return "根据自然语言问题做 schema linking：返回最相关的表结构（列名/类型/注释/外键）、"
                + "自然语言实体到字段的取值映射、以及相关业务口径。写 SQL 前必须先调用，严禁凭空猜表名字段名。"
                + "库里有约 20 张表（订单/商品/用户/支付/退款/物流/优惠券/库存/会员等），务必按检索结果写。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{"query":{"type":"string",
                "description":"检索用的自然语言描述，含业务对象、指标、涉及的实体，例如：顺丰的妥投率、手机数码品类销售额排行、金卡会员的客单价"}},
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

        List<SchemaDoc> hits;
        try {
            hits = retriever.retrieve(query, props.rag().topK());
        } catch (Exception e) {
            log.warn("schema 检索失败: {}", e.getMessage());
            return ToolOutput.fail("schema 检索失败: " + e.getMessage());
        }
        if (hits.isEmpty()) {
            return ToolOutput.fail("未检索到相关表结构，请换个说法描述业务对象（如：订单、商品、支付、物流、优惠券）");
        }

        // 确定性实体链接：问题里出现的已知枚举值 → 字段
        List<ValueLink> valueLinks = linkValues(query);

        // 相关表 = 显式实体链接的表 + 检索命中的表/列/取值所属表（去重、限量）
        Set<String> relevantTables = new LinkedHashSet<>();
        for (ValueLink vl : valueLinks) {
            relevantTables.add(tableOf(vl.column()));
        }
        for (SchemaDoc d : hits) {
            if (!d.table().isBlank()) {
                relevantTables.add(d.table());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 相关表结构（严格按此写 SQL，未列出的字段不存在）\n");
        int rendered = 0;
        for (String tableName : relevantTables) {
            if (rendered >= MAX_TABLES) {
                break;
            }
            SchemaKnowledge.table(tableName).ifPresent(t -> sb.append(renderTable(t)));
            rendered++;
        }

        // 取值映射：显式命中优先，再补检索到的取值文档
        List<String> valueLines = new ArrayList<>();
        Set<String> mappedColumns = new LinkedHashSet<>();
        for (ValueLink vl : valueLinks) {
            valueLines.add("- \"" + vl.value() + "\" → " + vl.column() + "（" + vl.dimName() + "）");
            mappedColumns.add(vl.column());
        }
        for (SchemaDoc d : hits) {
            if ("value".equals(d.type()) && !mappedColumns.contains(d.name())) {
                findDim(d.name()).ifPresent(dv -> valueLines.add(
                        "- " + dv.dimName() + " → " + dv.column() + "（取值: " + String.join("/", dv.values()) + "）"));
                mappedColumns.add(d.name());
            }
        }
        if (!valueLines.isEmpty()) {
            sb.append("\n## 取值映射（自然语言实体 → 字段）\n").append(String.join("\n", valueLines)).append("\n");
        }

        // 相关业务口径
        List<String> termTexts = hits.stream().filter(d -> "term".equals(d.type())).map(SchemaDoc::text).toList();
        if (!termTexts.isEmpty()) {
            sb.append("\n## 相关业务口径\n").append(String.join("\n\n", termTexts)).append("\n");
        }

        log.info("schema_search: query=\"{}\" 相关表={} 实体链接={}",
                query, relevantTables.stream().limit(MAX_TABLES).toList(),
                valueLinks.stream().map(ValueLink::value).toList());
        return ToolOutput.ok(sb.toString().strip());
    }

    /** 命中的表渲染成完整列清单 */
    private static String renderTable(Tbl t) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n### ").append(t.name()).append("（").append(t.zh()).append("）— ").append(t.purpose()).append("\n");
        for (Col col : t.cols()) {
            sb.append("- ").append(col.name()).append(" ").append(col.type())
                    .append(": ").append(col.comment()).append("\n");
        }
        return sb.toString();
    }

    /** 扫描问题里出现的已知枚举值，做确定性实体链接（子串匹配，中文字面量最有效） */
    private List<ValueLink> linkValues(String query) {
        String q = query.toLowerCase();
        // 用 map 按 column 去重，避免同列多值刷屏；保留首个命中的值
        Map<String, ValueLink> byColumn = new LinkedHashMap<>();
        for (DimValue dv : SchemaKnowledge.dimValues()) {
            for (String v : dv.values()) {
                if (v.length() >= 2 && q.contains(v.toLowerCase()) && !byColumn.containsKey(dv.column())) {
                    byColumn.put(dv.column(), new ValueLink(v, dv.column(), dv.dimName()));
                }
            }
        }
        return new ArrayList<>(byColumn.values());
    }

    private java.util.Optional<DimValue> findDim(String column) {
        return SchemaKnowledge.dimValues().stream().filter(dv -> dv.column().equals(column)).findFirst();
    }

    private static String tableOf(String column) {
        int dot = column.indexOf('.');
        return dot > 0 ? column.substring(0, dot) : column;
    }

    private record ValueLink(String value, String column, String dimName) {
    }
}
