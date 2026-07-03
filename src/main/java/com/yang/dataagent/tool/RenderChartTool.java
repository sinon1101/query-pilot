package com.yang.dataagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把模型给出的图表意图（类型 + 标题 + 分类 + 数据系列）组装成 ECharts option JSON。
 * option 由服务端确定性构建，模型只提供数据——避免模型手写 option 引入的格式错误，
 * 也把配色、坐标轴等视觉规范固定在代码里。校验失败原文返回给模型触发修正。
 */
@Component
public class RenderChartTool implements AgentTool {

    /**
     * 分类色板（固定顺序分配，不循环）：相邻色对的色觉障碍可分性已通过校验，
     * 顺序即安全机制，勿重排。取自经过验证的默认数据可视化色板。
     */
    private static final List<String> CATEGORICAL_COLORS = List.of(
            "#2a78d6", "#1baf7a", "#eda100", "#008300",
            "#4a3aa7", "#e34948", "#e87ba4", "#eb6834");

    private static final String INK_PRIMARY = "#0b0b0b";
    private static final String INK_SECONDARY = "#52514e";
    private static final String INK_MUTED = "#898781";
    private static final String GRIDLINE = "#e1e0d9";
    private static final String AXIS_LINE = "#c3c2b7";
    private static final String SURFACE = "#fcfcfb";

    private final ObjectMapper objectMapper;

    public RenderChartTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "render_chart";
    }

    @Override
    public String description() {
        return "根据查询结果生成 ECharts 图表。chartType 选择：分类对比/排行用 bar，"
                + "时间趋势用 line，占比构成用 pie（仅单系列且分类不超过 8 个）。"
                + "数据必须来自 execute_sql 的真实查询结果，不得编造。"
                + "成功后无需向用户描述图表配置，直接给出文字结论。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{
                "chartType":{"type":"string","enum":["bar","line","pie"],"description":"图表类型"},
                "title":{"type":"string","description":"图表标题（含时间范围等口径）"},
                "categories":{"type":"array","items":{"type":"string"},"description":"分类轴：品类名/日期/城市等"},
                "series":{"type":"array","items":{"type":"object","properties":{
                "name":{"type":"string","description":"系列名，如 销售额（元）"},
                "data":{"type":"array","items":{"type":"number"},"description":"数值，与 categories 一一对应"}},
                "required":["name","data"]},"description":"数据系列，通常 1 个"}},
                "required":["chartType","title","categories","series"]}""";
    }

    @Override
    public ToolOutput execute(String argumentsJson) {
        JsonNode args;
        try {
            args = objectMapper.readTree(argumentsJson);
        } catch (Exception e) {
            return ToolOutput.fail("工具参数不是合法 JSON: " + e.getMessage());
        }

        String chartType = args.path("chartType").asText("");
        String title = args.path("title").asText("");
        JsonNode categories = args.path("categories");
        JsonNode series = args.path("series");

        String problem = validate(chartType, categories, series);
        if (problem != null) {
            return ToolOutput.fail("图表参数校验不通过: " + problem);
        }

        ObjectNode option = switch (chartType) {
            case "pie" -> buildPieOption(title, categories, series.get(0));
            default -> buildAxisOption(chartType, title, categories, series);
        };
        try {
            return ToolOutput.ok(objectMapper.writeValueAsString(option));
        } catch (Exception e) {
            return ToolOutput.fail("option 序列化失败: " + e.getMessage());
        }
    }

    private String validate(String chartType, JsonNode categories, JsonNode series) {
        if (!List.of("bar", "line", "pie").contains(chartType)) {
            return "chartType 只支持 bar/line/pie，收到: " + chartType;
        }
        if (!categories.isArray() || categories.isEmpty()) {
            return "categories 必须是非空数组";
        }
        if (!series.isArray() || series.isEmpty()) {
            return "series 必须是非空数组";
        }
        if (series.size() > CATEGORICAL_COLORS.size()) {
            return "series 最多 " + CATEGORICAL_COLORS.size() + " 个";
        }
        for (JsonNode s : series) {
            if (!s.path("data").isArray() || s.path("data").size() != categories.size()) {
                return "系列 \"" + s.path("name").asText() + "\" 的 data 长度必须与 categories 一致";
            }
        }
        if ("pie".equals(chartType)) {
            if (series.size() != 1) {
                return "pie 只支持单系列";
            }
            if (categories.size() > CATEGORICAL_COLORS.size()) {
                return "pie 分类不超过 " + CATEGORICAL_COLORS.size() + " 个，分类多时改用 bar";
            }
        }
        return null;
    }

    /** bar/line 共用的直角坐标系 option */
    private ObjectNode buildAxisOption(String chartType, String title, JsonNode categories, JsonNode series) {
        ObjectNode option = baseOption(title);

        ObjectNode tooltip = option.putObject("tooltip");
        tooltip.put("trigger", "axis");
        tooltip.putObject("axisPointer").put("type", "bar".equals(chartType) ? "shadow" : "line");

        boolean multiSeries = series.size() >= 2;
        if (multiSeries) {
            ObjectNode legend = option.putObject("legend");
            legend.put("top", 4).put("right", 8);
            legend.putObject("textStyle").put("color", INK_SECONDARY);
        }

        ObjectNode grid = option.putObject("grid");
        grid.put("left", 8).put("right", 16).put("top", multiSeries ? 56 : 44).put("bottom", 8)
                .put("containLabel", true);

        ObjectNode xAxis = option.putObject("xAxis");
        xAxis.put("type", "category");
        xAxis.set("data", categories.deepCopy());
        xAxis.putObject("axisLine").putObject("lineStyle").put("color", AXIS_LINE);
        xAxis.putObject("axisTick").put("show", false);
        xAxis.putObject("axisLabel").put("color", INK_MUTED).put("interval", 0).put("rotate",
                categories.size() > 6 ? 30 : 0);

        ObjectNode yAxis = option.putObject("yAxis");
        yAxis.put("type", "value");
        yAxis.putObject("splitLine").putObject("lineStyle").put("color", GRIDLINE);
        yAxis.putObject("axisLabel").put("color", INK_MUTED);

        ArrayNode seriesNode = option.putArray("series");
        for (JsonNode s : series) {
            ObjectNode one = seriesNode.addObject();
            one.put("name", s.path("name").asText());
            one.put("type", chartType);
            one.set("data", s.path("data").deepCopy());
            if ("bar".equals(chartType)) {
                one.put("barMaxWidth", 40);
                // 数据端 4px 圆角，基线端保持直角
                one.putObject("itemStyle").putArray("borderRadius").add(4).add(4).add(0).add(0);
            } else {
                one.putObject("lineStyle").put("width", 2);
                one.put("symbolSize", 8);
            }
        }
        return option;
    }

    private ObjectNode buildPieOption(String title, JsonNode categories, JsonNode single) {
        ObjectNode option = baseOption(title);
        option.putObject("tooltip").put("trigger", "item");

        ArrayNode seriesNode = option.putArray("series");
        ObjectNode pie = seriesNode.addObject();
        pie.put("name", single.path("name").asText());
        pie.put("type", "pie");
        pie.putArray("radius").add("40%").add("68%");
        pie.putArray("center").add("50%").add("56%");
        // 扇区之间留 2px 底色缝隙
        pie.putObject("itemStyle").put("borderColor", SURFACE).put("borderWidth", 2);
        ObjectNode label = pie.putObject("label");
        label.put("color", INK_SECONDARY).put("formatter", "{b} {d}%");

        ArrayNode data = pie.putArray("data");
        for (int i = 0; i < categories.size(); i++) {
            data.addObject()
                    .put("name", categories.get(i).asText())
                    .set("value", single.path("data").get(i).deepCopy());
        }
        return option;
    }

    private ObjectNode baseOption(String title) {
        ObjectNode option = objectMapper.createObjectNode();
        ArrayNode color = option.putArray("color");
        CATEGORICAL_COLORS.forEach(color::add);
        ObjectNode titleNode = option.putObject("title");
        titleNode.put("text", title).put("left", 8).put("top", 4);
        titleNode.putObject("textStyle").put("color", INK_PRIMARY).put("fontSize", 14).put("fontWeight", 600);
        return option;
    }
}
