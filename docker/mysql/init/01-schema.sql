-- 业务样例库（电商）：与应用自身的 dataagent 库隔离。
-- Agent 的 execute_sql 工具使用只读账号 agent_ro，仅对 biz 库有 SELECT 权限，
-- 即使 SQL 校验被绕过，数据库权限层也能兜底（纵深防御）。

-- 初始化脚本由容器内 mysql client 执行，client 默认 latin1，
-- 不设 NAMES 会把 UTF-8 的中文（表注释、样例数据）双重编码写坏
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS biz DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'agent_ro'@'%' IDENTIFIED BY 'agent_ro123';
GRANT SELECT ON biz.* TO 'agent_ro'@'%';

USE biz;

CREATE TABLE users (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL COMMENT '用户名',
    city       VARCHAR(32)  NOT NULL COMMENT '所在城市',
    gender     ENUM('M','F') NOT NULL COMMENT '性别：M 男 / F 女',
    age        TINYINT UNSIGNED NOT NULL COMMENT '年龄',
    created_at DATETIME     NOT NULL COMMENT '注册时间',
    KEY idx_city (city)
) COMMENT '用户表';

CREATE TABLE products (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(128)  NOT NULL COMMENT '商品名称',
    category   VARCHAR(32)   NOT NULL COMMENT '品类（冗余的品类名，历史遗留；规范维度见 category 表）',
    brand_id   BIGINT UNSIGNED NULL COMMENT '品牌id → brand.id（部分白牌商品为空）',
    price      DECIMAL(10,2) NOT NULL COMMENT '售价（元）',
    created_at DATETIME      NOT NULL COMMENT '上架时间',
    KEY idx_category (category),
    KEY idx_brand (brand_id)
) COMMENT '商品表';

CREATE TABLE orders (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT UNSIGNED NOT NULL COMMENT '下单用户 id',
    order_time   DATETIME        NOT NULL COMMENT '下单时间',
    status       ENUM('paid','shipped','completed','cancelled','refunded') NOT NULL COMMENT '订单状态',
    total_amount DECIMAL(12,2)   NOT NULL DEFAULT 0 COMMENT '订单总金额（元）',
    KEY idx_user (user_id),
    KEY idx_order_time (order_time)
) COMMENT '订单表';

CREATE TABLE order_items (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT UNSIGNED NOT NULL COMMENT '订单 id',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '商品 id',
    quantity   INT UNSIGNED    NOT NULL COMMENT '购买数量',
    unit_price DECIMAL(10,2)   NOT NULL COMMENT '成交单价（元）',
    KEY idx_order (order_id),
    KEY idx_product (product_id)
) COMMENT '订单明细表';

-- ============================================================================
-- 以下为第二版扩展表：把 4 表 demo 库扩成 ~20 表的准真实电商库。
-- 刻意引入的"脏"，用于逼出真正的 schema linking 能力：
--   1) status 一词多义：orders/payment/refund/shipment/user_coupon 各有 status，枚举完全不同
--   2) 时间字段命名不一致：created_at / order_time / gmt_create / gmt_modified /
--      create_time / pay_time / apply_time / ship_time …（真实公司多团队多年代的通病）
--   3) 金额字段一词多义：total_amount / unit_price / pay_amount / refund_amount / youhui_jine
--   4) 遗留拼音表 t_order_ext：beizhu / youhui_jine / yhq_id（老系统直译命名）
-- 无外键约束（与前 4 表一致），仅建索引，避免灌数顺序与参照完整性负担。
-- ============================================================================

-- ---------- 用户域 ----------

CREATE TABLE member_level (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    level_name VARCHAR(16)  NOT NULL COMMENT '等级名：普通会员/银卡会员/金卡会员/钻石会员',
    min_growth INT UNSIGNED NOT NULL COMMENT '达到该等级所需的最小成长值',
    discount   DECIMAL(3,2) NOT NULL COMMENT '会员折扣，如 0.95 表示 95 折',
    gmt_create DATETIME     NOT NULL COMMENT '创建时间'
) COMMENT '会员等级字典表';

CREATE TABLE user_member (
    user_id      BIGINT UNSIGNED PRIMARY KEY COMMENT '用户 id → users.id（一人一条会员信息）',
    level_id     BIGINT UNSIGNED NOT NULL COMMENT '会员等级 id → member_level.id',
    growth_value INT UNSIGNED    NOT NULL COMMENT '成长值（累计消费/活跃换算）',
    points       INT UNSIGNED    NOT NULL COMMENT '当前可用积分',
    join_time    DATETIME        NOT NULL COMMENT '入会时间',
    KEY idx_level (level_id)
) COMMENT '用户会员信息表';

CREATE TABLE user_address (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户 id → users.id',
    receiver    VARCHAR(32)  NOT NULL COMMENT '收件人姓名',
    phone       VARCHAR(20)  NOT NULL COMMENT '联系电话',
    province    VARCHAR(16)  NOT NULL COMMENT '省',
    city        VARCHAR(16)  NOT NULL COMMENT '市（注意：与 users.city 未必一致，收货地不等于注册地）',
    district    VARCHAR(24)  NOT NULL COMMENT '区/县',
    detail_addr VARCHAR(128) NOT NULL COMMENT '详细地址',
    is_default  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认地址：1 是 0 否',
    gmt_create  DATETIME     NOT NULL COMMENT '创建时间',
    KEY idx_user (user_id)
) COMMENT '用户收货地址表';

-- ---------- 商品域 ----------

CREATE TABLE brand (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    brand_name VARCHAR(32) NOT NULL COMMENT '品牌名',
    country    VARCHAR(16) NOT NULL COMMENT '品牌国别',
    gmt_create DATETIME    NOT NULL COMMENT '创建时间',
    KEY idx_brand_name (brand_name)
) COMMENT '品牌表';

CREATE TABLE category (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    parent_id  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父品类 id，0 表示一级品类',
    cat_name   VARCHAR(32)  NOT NULL COMMENT '品类名（一级品类的 cat_name 与 products.category 取值一致）',
    cat_level  TINYINT      NOT NULL COMMENT '层级：1 一级 / 2 二级',
    sort_order INT          NOT NULL DEFAULT 0 COMMENT '展示排序',
    gmt_create DATETIME     NOT NULL COMMENT '创建时间',
    KEY idx_parent (parent_id)
) COMMENT '品类字典表（含二级层级）';

CREATE TABLE warehouse (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    wh_name    VARCHAR(32) NOT NULL COMMENT '仓库名',
    region     VARCHAR(16) NOT NULL COMMENT '所在大区：华东/华北/华南/华中/西南',
    gmt_create DATETIME    NOT NULL COMMENT '创建时间'
) COMMENT '仓库表';

CREATE TABLE inventory (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    product_id   BIGINT UNSIGNED NOT NULL COMMENT '商品 id → products.id',
    warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '仓库 id → warehouse.id',
    stock_qty    INT UNSIGNED    NOT NULL COMMENT '当前可用库存件数（分仓存储，商品总库存需按 product_id 汇总多仓）',
    safety_stock INT UNSIGNED    NOT NULL COMMENT '安全库存阈值，低于它需补货',
    gmt_modified DATETIME        NOT NULL COMMENT '库存最后变更时间',
    KEY idx_product (product_id),
    KEY idx_warehouse (warehouse_id)
) COMMENT '库存表（商品×仓库多行）';

CREATE TABLE product_review (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    product_id  BIGINT UNSIGNED NOT NULL COMMENT '商品 id → products.id',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '评价用户 id → users.id',
    order_id    BIGINT UNSIGNED NOT NULL COMMENT '来源订单 id → orders.id',
    rating      TINYINT      NOT NULL COMMENT '评分 1~5 星',
    content     VARCHAR(255) NOT NULL COMMENT '评价内容',
    create_time DATETIME     NOT NULL COMMENT '评价时间',
    KEY idx_product (product_id),
    KEY idx_rating (rating)
) COMMENT '商品评价表';

-- ---------- 交易域（注意各表 status 枚举互不相同）----------

CREATE TABLE payment (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT UNSIGNED NOT NULL COMMENT '订单 id → orders.id',
    pay_channel VARCHAR(16)   NOT NULL COMMENT '支付渠道：alipay/wechat/unionpay/balance',
    pay_amount  DECIMAL(12,2) NOT NULL COMMENT '实付金额（元），可能因优惠券小于订单总额',
    pay_time    DATETIME      NOT NULL COMMENT '支付时间',
    status      VARCHAR(16)   NOT NULL COMMENT '支付状态：success 成功 / failed 失败 / pending 待支付 / closed 已关闭',
    KEY idx_order (order_id),
    KEY idx_status (status)
) COMMENT '支付流水表';

CREATE TABLE refund (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    order_id      BIGINT UNSIGNED NOT NULL COMMENT '订单 id → orders.id',
    refund_amount DECIMAL(12,2) NOT NULL COMMENT '退款金额（元）',
    reason        VARCHAR(64)   NOT NULL COMMENT '退款原因',
    apply_time    DATETIME      NOT NULL COMMENT '退款申请时间',
    finish_time   DATETIME      NULL COMMENT '退款完成时间，未完成为空',
    status        VARCHAR(16)   NOT NULL COMMENT '退款状态：applied 已申请 / approved 已同意 / rejected 已驳回 / refunded 已退款',
    KEY idx_order (order_id),
    KEY idx_status (status)
) COMMENT '退款单表';

CREATE TABLE shipment (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT UNSIGNED NOT NULL COMMENT '订单 id → orders.id',
    carrier      VARCHAR(16) NOT NULL COMMENT '承运商：顺丰/中通/圆通/京东物流/韵达',
    tracking_no  VARCHAR(32) NOT NULL COMMENT '运单号',
    ship_time    DATETIME    NOT NULL COMMENT '发货时间',
    deliver_time DATETIME    NULL COMMENT '妥投（签收）时间，未签收为空',
    status       VARCHAR(16) NOT NULL COMMENT '物流状态：pending 待发货 / shipped 已发货 / delivered 已妥投 / rejected 已拒收',
    KEY idx_order (order_id),
    KEY idx_status (status)
) COMMENT '物流发货表（妥投=delivered，拒收=rejected）';

-- ---------- 营销域 ----------

CREATE TABLE coupon (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    coupon_name    VARCHAR(48)   NOT NULL COMMENT '优惠券名称',
    coupon_type    VARCHAR(16)   NOT NULL COMMENT '类型：full_reduction 满减 / discount 折扣',
    threshold      DECIMAL(10,2) NOT NULL COMMENT '使用门槛（满多少元可用），折扣券为 0',
    discount_value DECIMAL(10,2) NOT NULL COMMENT '满减券为减免金额；折扣券为折扣率如 0.90',
    valid_from     DATE          NOT NULL COMMENT '生效日',
    valid_to       DATE          NOT NULL COMMENT '失效日',
    gmt_create     DATETIME      NOT NULL COMMENT '创建时间'
) COMMENT '优惠券模板表';

CREATE TABLE user_coupon (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT UNSIGNED NOT NULL COMMENT '领券用户 id → users.id',
    coupon_id    BIGINT UNSIGNED NOT NULL COMMENT '优惠券模板 id → coupon.id',
    order_id     BIGINT UNSIGNED NULL COMMENT '核销所用订单 id → orders.id，未核销为空',
    status       VARCHAR(16)  NOT NULL COMMENT '状态：unused 未使用 / used 已核销 / expired 已过期',
    receive_time DATETIME     NOT NULL COMMENT '领取时间',
    use_time     DATETIME     NULL COMMENT '核销时间，未核销为空',
    KEY idx_user (user_id),
    KEY idx_coupon (coupon_id),
    KEY idx_status (status)
) COMMENT '用户优惠券表（核销=used）';

CREATE TABLE promotion (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    campaign_name VARCHAR(48) NOT NULL COMMENT '活动名称，如 双11大促/618年中/年货节',
    promo_type    VARCHAR(16) NOT NULL COMMENT '活动类型：full_reduction 满减 / seckill 秒杀 / discount 折扣',
    start_time    DATETIME    NOT NULL COMMENT '活动开始时间',
    end_time      DATETIME    NOT NULL COMMENT '活动结束时间',
    gmt_create    DATETIME    NOT NULL COMMENT '创建时间'
) COMMENT '营销活动表';

-- ---------- 遗留系统表（拼音命名，真实的历史包袱）----------

CREATE TABLE t_order_ext (
    order_id     BIGINT UNSIGNED PRIMARY KEY COMMENT '订单 id → orders.id（一单一条扩展信息）',
    beizhu       VARCHAR(255)  NULL COMMENT '备注（buyer remark）',
    fapiao_type  TINYINT       NOT NULL DEFAULT 0 COMMENT '发票类型：0 不开票 / 1 个人 / 2 企业',
    yhq_id       BIGINT UNSIGNED NULL COMMENT '所用优惠券 id → user_coupon.id（拼音：优惠券）',
    youhui_jine  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '优惠金额（拼音：优惠金额），下单立减+券抵扣合计',
    gmt_create   DATETIME      NOT NULL COMMENT '创建时间'
) COMMENT '订单扩展表（老系统遗留，字段拼音命名）';
