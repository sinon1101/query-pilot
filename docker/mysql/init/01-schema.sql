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
    category   VARCHAR(32)   NOT NULL COMMENT '品类',
    price      DECIMAL(10,2) NOT NULL COMMENT '售价（元）',
    created_at DATETIME      NOT NULL COMMENT '上架时间',
    KEY idx_category (category)
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
