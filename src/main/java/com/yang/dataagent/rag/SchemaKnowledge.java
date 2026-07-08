package com.yang.dataagent.rag;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * schema 知识库的单一数据源（catalog）。既用于生成向量库文档（多粒度：表 / 列 / 取值 / 口径），
 * 又作为 schema_search 结构化输出时"把命中的表渲染成完整列清单"的依据，同时充当项目的活文档。
 * <p>
 * 内容必须与 {@code docker/mysql/init/01-schema.sql} 保持一致；表结构变更时同步这里。
 * <p>
 * 多粒度的动机：19 张表、脏命名（status 一词多义、gmt_create/order_time 混用、拼音遗留表），
 * 单表粒度的检索既不精准也塞不下上下文。拆成
 * <ul>
 *   <li>表摘要文档——回答"哪些表相关"</li>
 *   <li>列级文档——回答"哪个字段是这个指标/维度"（列名+类型+注释+所属表）</li>
 *   <li>取值文档——把自然语言实体（顺丰、手机数码、金卡）映射到具体字段（实体链接）</li>
 *   <li>口径文档——业务黑话（GMV/履约/核销/动销）的确定性定义</li>
 * </ul>
 */
public final class SchemaKnowledge {

    private SchemaKnowledge() {
    }

    // ================= catalog 数据模型 =================

    public record Col(String name, String type, String comment) {
    }

    /** 一张表：英文名、中文名、用途、列、常见问法（用于向量召回的语义扩展） */
    public record Tbl(String name, String zh, String purpose, List<Col> cols, List<String> asks) {
    }

    /** 取值映射：某维度字段的可枚举取值，支撑"实体 → 字段"链接 */
    public record DimValue(String column, String dimName, List<String> values, String note) {
    }

    /** 业务口径/术语定义 */
    public record Term(String name, List<String> aliases, String definition) {
    }

    /** 向量库文档（含用于分组渲染的元信息）；type ∈ table/column/value/term */
    public record SchemaDoc(String id, String type, String table, String name, String text) {
        public Document toDocument() {
            return Document.builder()
                    .id(id)
                    .text(text)
                    .metadata(Map.of("type", type, "name", name, "table", table))
                    .build();
        }
    }

    private static Col c(String name, String type, String comment) {
        return new Col(name, type, comment);
    }

    // ================= 19 张表的定义 =================

    private static final List<Tbl> TABLES = List.of(
            new Tbl("users", "用户表", "一个用户一行；与订单表通过 orders.user_id = users.id 关联。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键，用户 id"),
                            c("username", "VARCHAR(64)", "用户名"),
                            c("city", "VARCHAR(32)", "用户注册时所在城市（注意：与收货地址 user_address.city 未必相同）"),
                            c("gender", "ENUM('M','F')", "性别：M 男 / F 女"),
                            c("age", "TINYINT UNSIGNED", "年龄"),
                            c("created_at", "DATETIME", "注册时间")),
                    List.of("用户画像", "城市分布", "男女比例", "年龄段", "新增用户", "注册")),

            new Tbl("products", "商品表", "一个商品一行；销量/销售额需通过 order_items 关联明细算。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键，商品 id"),
                            c("name", "VARCHAR(128)", "商品名称"),
                            c("category", "VARCHAR(32)", "品类名（冗余字段，历史遗留；规范维度见 category 表，二者一级品类取值一致）"),
                            c("brand_id", "BIGINT UNSIGNED", "品牌 id → brand.id；白牌/无品牌商品为 NULL"),
                            c("price", "DECIMAL(10,2)", "标价/售价（元）；成交价以 order_items.unit_price 为准"),
                            c("created_at", "DATETIME", "上架时间")),
                    List.of("品类", "品牌", "商品排行", "热销商品", "客单价", "上架")),

            new Tbl("orders", "订单表", "一笔订单一行；订单包含的商品在 order_items。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键，订单 id"),
                            c("user_id", "BIGINT UNSIGNED", "下单用户 id → users.id"),
                            c("order_time", "DATETIME", "下单时间（订单相关的时间维度都用它）"),
                            c("status", "ENUM", "订单状态：paid/shipped/completed/cancelled/refunded（有效销售额口径只算前三者）"),
                            c("total_amount", "DECIMAL(12,2)", "整单成交金额（元）；分品类/分商品拆不开，别用它算维度销售额")),
                    List.of("订单量", "下单", "成交", "月度趋势", "订单状态", "退款率", "取消率")),

            new Tbl("order_items", "订单明细表", "订单中的一个商品一行；销售额 = SUM(quantity*unit_price)。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键"),
                            c("order_id", "BIGINT UNSIGNED", "订单 id → orders.id"),
                            c("product_id", "BIGINT UNSIGNED", "商品 id → products.id"),
                            c("quantity", "INT UNSIGNED", "购买数量（销量 = SUM(quantity)）"),
                            c("unit_price", "DECIMAL(10,2)", "成交单价（元）；分品类/商品销售额都从这里算")),
                    List.of("销售额", "销量", "GMV", "品类排行", "商品排行", "件单价")),

            new Tbl("member_level", "会员等级字典表", "会员等级维表。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键，等级 id"),
                            c("level_name", "VARCHAR(16)", "等级名：普通会员/银卡会员/金卡会员/钻石会员"),
                            c("min_growth", "INT UNSIGNED", "达到该等级所需最小成长值"),
                            c("discount", "DECIMAL(3,2)", "会员折扣，如 0.95=95 折"),
                            c("gmt_create", "DATETIME", "创建时间")),
                    List.of("会员等级", "等级门槛", "会员折扣")),

            new Tbl("user_member", "用户会员信息表", "用户与会员等级的关系，一人一条。",
                    List.of(
                            c("user_id", "BIGINT UNSIGNED", "主键，用户 id → users.id"),
                            c("level_id", "BIGINT UNSIGNED", "会员等级 id → member_level.id"),
                            c("growth_value", "INT UNSIGNED", "成长值"),
                            c("points", "INT UNSIGNED", "当前可用积分"),
                            c("join_time", "DATETIME", "入会时间")),
                    List.of("会员", "成长值", "积分", "入会", "会员等级分布", "会员渗透率")),

            new Tbl("user_address", "用户收货地址表", "一个用户可有多个收货地址。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键"),
                            c("user_id", "BIGINT UNSIGNED", "用户 id → users.id"),
                            c("receiver", "VARCHAR(32)", "收件人姓名"),
                            c("phone", "VARCHAR(20)", "联系电话"),
                            c("province", "VARCHAR(16)", "省"),
                            c("city", "VARCHAR(16)", "收货城市（收货地，不等于 users.city 注册城市）"),
                            c("district", "VARCHAR(24)", "区/县"),
                            c("detail_addr", "VARCHAR(128)", "详细地址"),
                            c("is_default", "TINYINT", "是否默认地址：1 是 0 否"),
                            c("gmt_create", "DATETIME", "创建时间")),
                    List.of("收货地址", "收货城市", "地区分布", "默认地址")),

            new Tbl("brand", "品牌表", "品牌维表；products.brand_id 关联到这里。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键，品牌 id"),
                            c("brand_name", "VARCHAR(32)", "品牌名"),
                            c("country", "VARCHAR(16)", "品牌国别"),
                            c("gmt_create", "DATETIME", "创建时间")),
                    List.of("品牌", "品牌国别", "品牌排行", "品牌销售额")),

            new Tbl("category", "品类字典表", "品类维表，含一级/二级层级。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键，品类 id"),
                            c("parent_id", "BIGINT UNSIGNED", "父品类 id，0 表示一级品类"),
                            c("cat_name", "VARCHAR(32)", "品类名（一级品类 cat_name 与 products.category 取值一致）"),
                            c("cat_level", "TINYINT", "层级：1 一级 / 2 二级"),
                            c("sort_order", "INT", "展示排序"),
                            c("gmt_create", "DATETIME", "创建时间")),
                    List.of("品类层级", "一级品类", "二级品类")),

            new Tbl("warehouse", "仓库表", "仓库维表；inventory.warehouse_id 关联到这里。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键，仓库 id"),
                            c("wh_name", "VARCHAR(32)", "仓库名"),
                            c("region", "VARCHAR(16)", "所在大区：华东/华北/华南/华中/西南"),
                            c("gmt_create", "DATETIME", "创建时间")),
                    List.of("仓库", "大区", "区域库存")),

            new Tbl("inventory", "库存表", "商品×仓库多行；某商品总库存需按 product_id 汇总多仓。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键"),
                            c("product_id", "BIGINT UNSIGNED", "商品 id → products.id"),
                            c("warehouse_id", "BIGINT UNSIGNED", "仓库 id → warehouse.id"),
                            c("stock_qty", "INT UNSIGNED", "当前可用库存件数（分仓存储，总库存 = SUM(stock_qty)）"),
                            c("safety_stock", "INT UNSIGNED", "安全库存阈值，低于它需补货"),
                            c("gmt_modified", "DATETIME", "库存最后变更时间")),
                    List.of("库存", "缺货", "补货", "安全库存", "分仓库存", "库存最少")),

            new Tbl("product_review", "商品评价表", "一条评价一行，来源于订单。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键"),
                            c("product_id", "BIGINT UNSIGNED", "商品 id → products.id"),
                            c("user_id", "BIGINT UNSIGNED", "评价用户 id → users.id"),
                            c("order_id", "BIGINT UNSIGNED", "来源订单 id → orders.id"),
                            c("rating", "TINYINT", "评分 1~5 星"),
                            c("content", "VARCHAR(255)", "评价内容"),
                            c("create_time", "DATETIME", "评价时间")),
                    List.of("评价", "评分", "好评率", "差评", "平均评分")),

            new Tbl("payment", "支付流水表", "订单的支付流水；注意 status 枚举与 orders.status 完全不同。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键"),
                            c("order_id", "BIGINT UNSIGNED", "订单 id → orders.id"),
                            c("pay_channel", "VARCHAR(16)", "支付渠道：alipay/wechat/unionpay/balance"),
                            c("pay_amount", "DECIMAL(12,2)", "实付金额（元），可能因优惠券小于订单总额"),
                            c("pay_time", "DATETIME", "支付时间"),
                            c("status", "VARCHAR(16)", "支付状态：success/failed/pending/closed（与订单状态不是一回事）")),
                    List.of("支付", "实付金额", "支付渠道", "支付成功率")),

            new Tbl("refund", "退款单表", "订单的退款申请；status 又是另一套枚举。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键"),
                            c("order_id", "BIGINT UNSIGNED", "订单 id → orders.id"),
                            c("refund_amount", "DECIMAL(12,2)", "退款金额（元）"),
                            c("reason", "VARCHAR(64)", "退款原因"),
                            c("apply_time", "DATETIME", "退款申请时间"),
                            c("finish_time", "DATETIME", "退款完成时间，未完成为 NULL"),
                            c("status", "VARCHAR(16)", "退款状态：applied/approved/rejected/refunded")),
                    List.of("退款", "退款金额", "退款原因", "退款率", "退款状态")),

            new Tbl("shipment", "物流发货表", "订单的物流；妥投=delivered，拒收=rejected。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键"),
                            c("order_id", "BIGINT UNSIGNED", "订单 id → orders.id"),
                            c("carrier", "VARCHAR(16)", "承运商：顺丰/中通/圆通/京东物流/韵达"),
                            c("tracking_no", "VARCHAR(32)", "运单号"),
                            c("ship_time", "DATETIME", "发货时间"),
                            c("deliver_time", "DATETIME", "妥投（签收）时间，未签收为 NULL"),
                            c("status", "VARCHAR(16)", "物流状态：pending/shipped/delivered/rejected")),
                    List.of("物流", "发货", "妥投", "签收", "拒收", "履约", "承运商", "快递")),

            new Tbl("coupon", "优惠券模板表", "优惠券模板；用户领取的实例在 user_coupon。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键，优惠券模板 id"),
                            c("coupon_name", "VARCHAR(48)", "优惠券名称"),
                            c("coupon_type", "VARCHAR(16)", "类型：full_reduction 满减 / discount 折扣"),
                            c("threshold", "DECIMAL(10,2)", "使用门槛（满多少元），折扣券为 0"),
                            c("discount_value", "DECIMAL(10,2)", "满减券为减免金额；折扣券为折扣率如 0.90"),
                            c("valid_from", "DATE", "生效日"),
                            c("valid_to", "DATE", "失效日"),
                            c("gmt_create", "DATETIME", "创建时间")),
                    List.of("优惠券", "满减", "折扣券", "券模板")),

            new Tbl("user_coupon", "用户优惠券表", "用户领取的券；核销=used（use_time 非空）。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键"),
                            c("user_id", "BIGINT UNSIGNED", "领券用户 id → users.id"),
                            c("coupon_id", "BIGINT UNSIGNED", "优惠券模板 id → coupon.id"),
                            c("order_id", "BIGINT UNSIGNED", "核销所用订单 id → orders.id，未核销为 NULL"),
                            c("status", "VARCHAR(16)", "状态：unused 未使用 / used 已核销 / expired 已过期"),
                            c("receive_time", "DATETIME", "领取时间"),
                            c("use_time", "DATETIME", "核销时间，未核销为 NULL")),
                    List.of("领券", "核销", "核销率", "优惠券使用", "用户券")),

            new Tbl("promotion", "营销活动表", "营销活动/大促维表。",
                    List.of(
                            c("id", "BIGINT UNSIGNED", "主键"),
                            c("campaign_name", "VARCHAR(48)", "活动名称，如 双11大促/618年中/年货节"),
                            c("promo_type", "VARCHAR(16)", "活动类型：full_reduction/seckill/discount"),
                            c("start_time", "DATETIME", "活动开始时间"),
                            c("end_time", "DATETIME", "活动结束时间"),
                            c("gmt_create", "DATETIME", "创建时间")),
                    List.of("活动", "大促", "双11", "618", "秒杀", "年货节")),

            new Tbl("t_order_ext", "订单扩展表（遗留拼音命名）", "老系统遗留，字段用拼音；一单一条。",
                    List.of(
                            c("order_id", "BIGINT UNSIGNED", "主键，订单 id → orders.id"),
                            c("beizhu", "VARCHAR(255)", "备注（buyer remark；拼音：备注）"),
                            c("fapiao_type", "TINYINT", "发票类型：0 不开票 / 1 个人 / 2 企业（拼音：发票）"),
                            c("yhq_id", "BIGINT UNSIGNED", "所用优惠券 id → user_coupon.id（拼音：优惠券）"),
                            c("youhui_jine", "DECIMAL(10,2)", "优惠金额，下单立减+券抵扣合计（拼音：优惠金额）"),
                            c("gmt_create", "DATETIME", "创建时间")),
                    List.of("备注", "发票", "优惠金额", "订单扩展")));

    // ================= 取值映射（实体 → 字段）=================

    private static final List<DimValue> DIM_VALUES = List.of(
            new DimValue("products.category", "商品品类",
                    List.of("手机数码", "家用电器", "服饰鞋包", "美妆个护", "食品生鲜", "图书文教", "运动户外", "家居家装"),
                    "共 8 个一级品类，中文存储，直接用中文字面量匹配。"),
            new DimValue("orders.status", "订单状态",
                    List.of("paid", "shipped", "completed", "cancelled", "refunded"),
                    "已支付/已发货/已完成/已取消/已退款；有效销售额只算 paid+shipped+completed。"),
            new DimValue("payment.status", "支付状态",
                    List.of("success", "failed", "pending", "closed"),
                    "支付成功=success；与订单状态不是一回事。"),
            new DimValue("refund.status", "退款状态",
                    List.of("applied", "approved", "rejected", "refunded"),
                    "已申请/已同意/已驳回/已退款。"),
            new DimValue("shipment.status", "物流状态",
                    List.of("pending", "shipped", "delivered", "rejected"),
                    "待发货/已发货/已妥投(签收)/已拒收；妥投=delivered，拒收=rejected。"),
            new DimValue("shipment.carrier", "快递承运商",
                    List.of("顺丰", "中通", "圆通", "京东物流", "韵达"),
                    "用户提到具体快递公司名时，落到 shipment.carrier 过滤。"),
            new DimValue("payment.pay_channel", "支付渠道",
                    List.of("alipay", "wechat", "unionpay", "balance"),
                    "支付宝/微信/银联/余额。"),
            new DimValue("user_coupon.status", "优惠券状态",
                    List.of("unused", "used", "expired"),
                    "未使用/已核销/已过期；核销=used。"),
            new DimValue("member_level.level_name", "会员等级",
                    List.of("普通会员", "银卡会员", "金卡会员", "钻石会员"),
                    "四档会员等级。"),
            new DimValue("warehouse.region", "仓库大区",
                    List.of("华东", "华北", "华南", "华中", "西南"),
                    "五个大区。"),
            new DimValue("users.city", "用户注册城市",
                    List.of("北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安", "重庆"),
                    "users.city 是注册城市；若问收货城市用 user_address.city。"));

    // ================= 业务口径/黑话 =================

    private static final List<Term> TERMS = List.of(
            new Term("销售额 / GMV / 成交额", List.of("销售额", "GMV", "成交额", "营业额"),
                    "只统计有效订单 orders.status IN ('paid','shipped','completed')，排除 cancelled/refunded。"
                            + "金额用明细汇总 SUM(order_items.quantity*order_items.unit_price)，单位元。"
                            + "不要用 orders.total_amount 算分品类/分商品销售额（整单金额拆不到商品）。"),
            new Term("相对时间口径", List.of("上个月", "本月", "最近", "今年", "近30天", "近7天"),
                    "上个月: DATE_FORMAT(时间字段,'%Y-%m')=DATE_FORMAT(CURDATE()-INTERVAL 1 MONTH,'%Y-%m')；"
                            + "本月同理用 CURDATE()；最近30天: 时间字段>=CURDATE()-INTERVAL 30 DAY。"
                            + "时间字段：下单 orders.order_time、注册 users.created_at、支付 payment.pay_time、"
                            + "退款申请 refund.apply_time、发货 shipment.ship_time；严禁自己推算月初月末边界。"),
            new Term("客单价 / 件单价", List.of("客单价", "件单价"),
                    "客单价 = 有效订单销售额 / 有效订单数；件单价 = 销售额 / 销量(SUM(quantity))。"),
            new Term("退款率 / 取消率", List.of("退款率", "取消率"),
                    "退款率 = status='refunded' 订单数 / 总订单数；取消率 = status='cancelled' 订单数 / 总订单数（基于 orders 表）。"),
            new Term("复购", List.of("复购", "复购用户", "复购率"),
                    "复购用户 = 有效订单数>=2 的用户；复购率 = 复购用户数 / 有效下单用户数。"),
            new Term("履约率 / 妥投率", List.of("履约", "妥投", "签收率", "拒收率"),
                    "基于 shipment：妥投率 = status='delivered' 的物流数 / 已发货物流数；拒收率 = status='rejected' / 总物流数。"),
            new Term("核销率", List.of("核销", "核销率", "用券率"),
                    "基于 user_coupon：核销率 = status='used' 的券数 / 已领取券总数（COUNT(*)）。"),
            new Term("动销率", List.of("动销", "动销率", "有销售的商品占比"),
                    "动销率 = 统计期内有销售记录(出现在 order_items 且订单有效)的商品数 / 商品总数(products 计数)。"),
            new Term("库存口径", List.of("库存", "总库存", "缺货", "安全库存"),
                    "商品总库存 = 按 product_id 对 inventory.stock_qty 汇总(分仓)；缺货/需补货 = 总库存<安全库存 safety_stock。"),
            new Term("支付口径", List.of("实付", "支付成功率", "支付金额"),
                    "实付金额用 payment.pay_amount；支付成功 = payment.status='success'；支付成功率 = 成功支付数/支付流水总数。"),
            new Term("品类与品牌口径", List.of("品类", "品牌", "白牌"),
                    "品类可直接用 products.category（冗余字段）；品牌需 products.brand_id JOIN brand，"
                            + "brand_id 为 NULL 的是白牌/无品牌商品。"),
            new Term("城市口径（注册地 vs 收货地）", List.of("城市", "注册城市", "收货城市", "地区"),
                    "问用户来自哪个城市/城市分布用 users.city（注册城市）；问货发到哪/收货城市用 user_address.city。两者不同。"));

    // ================= 文档生成 =================

    /** 生成全部多粒度向量库文档：表 + 列 + 取值 + 口径。 */
    public static List<SchemaDoc> documents() {
        List<SchemaDoc> docs = new ArrayList<>();
        for (Tbl t : TABLES) {
            String colNames = String.join(", ", t.cols().stream().map(Col::name).toList());
            String tableText = "表 " + t.name() + "（" + t.zh() + "）\n用途: " + t.purpose()
                    + "\n列: " + colNames + "\n常见问法: " + String.join(", ", t.asks());
            docs.add(new SchemaDoc("table:" + t.name(), "table", t.name(), t.name(), tableText));
            for (Col col : t.cols()) {
                String colText = "列 " + t.name() + "." + col.name() + "（" + col.type() + "）: " + col.comment()
                        + "\n所属表: " + t.name() + "（" + t.zh() + "）";
                docs.add(new SchemaDoc("col:" + t.name() + "." + col.name(), "column", t.name(),
                        t.name() + "." + col.name(), colText));
            }
        }
        for (DimValue dv : DIM_VALUES) {
            String text = "取值映射: " + dv.dimName() + "\n字段: " + dv.column()
                    + "\n取值: " + String.join(", ", dv.values()) + "\n说明: " + dv.note();
            docs.add(new SchemaDoc("value:" + dv.column(), "value", tableOf(dv.column()), dv.column(), text));
        }
        for (Term term : TERMS) {
            String text = "业务口径: " + term.name() + "\n同义/触发词: " + String.join(", ", term.aliases())
                    + "\n定义: " + term.definition();
            docs.add(new SchemaDoc("term:" + term.name(), "term", "", term.name(), text));
        }
        return docs;
    }

    /** 按表名取表定义（供 schema_search 把命中的表渲染成完整列清单）。 */
    public static Optional<Tbl> table(String name) {
        return TABLES.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    public static List<DimValue> dimValues() {
        return DIM_VALUES;
    }

    private static String tableOf(String column) {
        int dot = column.indexOf('.');
        return dot > 0 ? column.substring(0, dot) : "";
    }
}
