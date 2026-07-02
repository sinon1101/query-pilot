package com.yang.dataagent.rag;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * schema 知识库的文档定义：每张表一个文档（DDL + 业务说明 + 常见问法），
 * 每条业务口径/术语一个文档。文档 id 固定，重复灌库时按 id 覆盖，天然幂等。
 * <p>
 * 内容与 docker/mysql/init/01-schema.sql 保持一致；表结构变更时需同步这里。
 */
public final class SchemaKnowledge {

    private SchemaKnowledge() {
    }

    public static List<Document> documents() {
        return List.of(
                table("users", """
                        表名: users（用户表）
                        CREATE TABLE users (
                            id         BIGINT UNSIGNED PRIMARY KEY,
                            username   VARCHAR(64)  NOT NULL COMMENT '用户名',
                            city       VARCHAR(32)  NOT NULL COMMENT '所在城市，如：北京/上海/广州/深圳等',
                            gender     ENUM('M','F') NOT NULL COMMENT '性别：M 男 / F 女',
                            age        TINYINT UNSIGNED NOT NULL COMMENT '年龄',
                            created_at DATETIME     NOT NULL COMMENT '注册时间'
                        ) COMMENT '用户表';
                        业务说明: 一个用户一行。与订单表通过 orders.user_id = users.id 关联。
                        常见问法: 用户画像、城市分布、男女比例、年龄段、新增用户、注册。
                        """),
                table("products", """
                        表名: products（商品表）
                        CREATE TABLE products (
                            id         BIGINT UNSIGNED PRIMARY KEY,
                            name       VARCHAR(128)  NOT NULL COMMENT '商品名称',
                            category   VARCHAR(32)   NOT NULL COMMENT '品类',
                            price      DECIMAL(10,2) NOT NULL COMMENT '售价（元）',
                            created_at DATETIME      NOT NULL COMMENT '上架时间'
                        ) COMMENT '商品表';
                        业务说明: 一个商品一行。销量/销售额需通过 order_items.product_id = products.id 关联明细算。
                        常见问法: 品类、商品排行、热销商品、客单价、上架。
                        """),
                table("orders", """
                        表名: orders（订单表）
                        CREATE TABLE orders (
                            id           BIGINT UNSIGNED PRIMARY KEY,
                            user_id      BIGINT UNSIGNED NOT NULL COMMENT '下单用户 id → users.id',
                            order_time   DATETIME        NOT NULL COMMENT '下单时间',
                            status       ENUM('paid','shipped','completed','cancelled','refunded') NOT NULL COMMENT '订单状态',
                            total_amount DECIMAL(12,2)   NOT NULL COMMENT '订单总金额（元）'
                        ) COMMENT '订单表';
                        业务说明: 一笔订单一行。订单包含的商品在 order_items（order_items.order_id = orders.id）。
                        常见问法: 订单量、下单、成交、月度趋势、订单状态、退款率、取消率。
                        """),
                table("order_items", """
                        表名: order_items（订单明细表）
                        CREATE TABLE order_items (
                            id         BIGINT UNSIGNED PRIMARY KEY,
                            order_id   BIGINT UNSIGNED NOT NULL COMMENT '订单 id → orders.id',
                            product_id BIGINT UNSIGNED NOT NULL COMMENT '商品 id → products.id',
                            quantity   INT UNSIGNED    NOT NULL COMMENT '购买数量',
                            unit_price DECIMAL(10,2)   NOT NULL COMMENT '成交单价（元）'
                        ) COMMENT '订单明细表';
                        业务说明: 订单中的一个商品一行。销售额 = SUM(quantity * unit_price)。
                        算品类/商品维度的销售额需三表关联: order_items → orders（过滤有效状态和时间）→ products（取品类）。
                        常见问法: 销售额、销量、GMV、品类排行、商品排行。
                        """),
                term("sales-caliber", """
                        业务口径: 销售额 / GMV / 成交额
                        定义: 只统计有效订单，即 orders.status IN ('paid','shipped','completed')，
                        排除 cancelled（已取消）和 refunded（已退款）。
                        金额一律用明细汇总: SUM(order_items.quantity * order_items.unit_price)，单位：元。
                        不要用 orders.total_amount 算分品类/分商品的销售额（它是整单金额，无法拆到商品）。
                        """),
                term("time-caliber", """
                        业务口径: 相对时间（上个月、本月、最近 N 天、今年）
                        基于当前日期计算，MySQL 写法示例:
                        上个月: DATE_FORMAT(order_time, '%Y-%m') = DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m')
                        本月: DATE_FORMAT(order_time, '%Y-%m') = DATE_FORMAT(CURDATE(), '%Y-%m')
                        最近 30 天: order_time >= CURDATE() - INTERVAL 30 DAY
                        时间字段: 订单用 orders.order_time，用户注册用 users.created_at。
                        """),
                term("categories", """
                        业务口径: 商品品类（products.category）的取值范围
                        手机数码 / 家用电器 / 服饰鞋包 / 美妆个护 / 食品生鲜 / 图书文教 / 运动户外 / 家居家装
                        共 8 个品类，中文存储，查询时直接用中文字面量匹配。
                        """),
                term("metrics", """
                        业务口径: 常用衍生指标
                        客单价 = 有效订单销售额 / 有效订单数。
                        件单价 = 销售额 / 销量（SUM(quantity)）。
                        退款率 = refunded 订单数 / 总订单数；取消率 = cancelled 订单数 / 总订单数。
                        复购用户 = 有效订单数 >= 2 的用户。
                        """));
    }

    private static Document table(String name, String text) {
        return doc("table:" + name, "table", name, text);
    }

    private static Document term(String name, String text) {
        return doc("term:" + name, "term", name, text);
    }

    private static Document doc(String id, String type, String name, String text) {
        return Document.builder()
                .id(id)
                .text(text.strip())
                .metadata(Map.of("type", type, "name", name))
                .build();
    }
}
