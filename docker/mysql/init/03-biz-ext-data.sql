-- 第二版扩展表的样例数据。必须在 02-data.sql 之后执行（依赖 orders.total_amount 已回填）。
-- 全部用固定种子 RAND(seed) 生成、日期相对 NOW()，可复现且"上个月/近30天"类问题恒有数据。
SET NAMES utf8mb4;
USE biz;
SET SESSION cte_max_recursion_depth = 20000;

-- ============================ 用户域 ============================

-- 会员等级：4 档
INSERT INTO member_level (level_name, min_growth, discount, gmt_create) VALUES
('普通会员',      0, 1.00, NOW() - INTERVAL 500 DAY),
('银卡会员',   1000, 0.98, NOW() - INTERVAL 500 DAY),
('金卡会员',   5000, 0.95, NOW() - INTERVAL 500 DAY),
('钻石会员',  20000, 0.90, NOW() - INTERVAL 500 DAY);

-- 用户会员信息：300 个用户一人一条，成长值决定等级
INSERT INTO user_member (user_id, level_id, growth_value, points, join_time)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 300
)
SELECT
    n,
    CASE
        WHEN g >= 20000 THEN 4
        WHEN g >=  5000 THEN 3
        WHEN g >=  1000 THEN 2
        ELSE 1
    END,
    g,
    FLOOR(g / 10),
    (SELECT created_at FROM users WHERE id = n) + INTERVAL FLOOR(RAND(n * 3) * 30) DAY
FROM (
    SELECT n, FLOOR(RAND(n * 101) * 30000) AS g
    FROM seq
) t;

-- 收货地址：每个用户 1~2 条；收货城市与注册城市可能不同（刻意制造 users.city vs user_address.city 的歧义）
INSERT INTO user_address (user_id, receiver, phone, province, city, district, detail_addr, is_default, gmt_create)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 420
)
SELECT
    1 + FLOOR(RAND(n * 5) * 300),
    CONCAT('收件人', LPAD(n, 4, '0')),
    CONCAT('138', LPAD(FLOOR(RAND(n * 7) * 100000000), 8, '0')),
    ELT(1 + FLOOR(RAND(n * 9) * 8), '北京市','上海市','广东省','浙江省','四川省','湖北省','江苏省','陕西省'),
    ELT(1 + FLOOR(RAND(n * 9) * 8), '北京','上海','广州','杭州','成都','武汉','南京','西安'),
    CONCAT('第', 1 + FLOOR(RAND(n * 11) * 12), '区'),
    CONCAT(ELT(1 + FLOOR(RAND(n * 13) * 4), '幸福路','建设大道','科技园','人民路'), FLOOR(RAND(n * 15) * 200), '号'),
    IF(RAND(n * 17) < 0.6, 1, 0),
    NOW() - INTERVAL FLOOR(RAND(n * 19) * 500) DAY
FROM seq;

-- ============================ 商品域 ============================

-- 品牌：brand_name 取商品名中的可匹配子串，便于后面按 LIKE 回填 products.brand_id
INSERT INTO brand (brand_name, country, gmt_create) VALUES
('小米','中国', NOW()-INTERVAL 600 DAY), ('华为','中国', NOW()-INTERVAL 600 DAY),
('海尔','中国', NOW()-INTERVAL 600 DAY), ('美的','中国', NOW()-INTERVAL 600 DAY),
('戴森','英国', NOW()-INTERVAL 600 DAY), ('九阳','中国', NOW()-INTERVAL 600 DAY),
('优衣库','日本', NOW()-INTERVAL 600 DAY), ('耐克','美国', NOW()-INTERVAL 600 DAY),
('阿迪达斯','德国', NOW()-INTERVAL 600 DAY), ('波司登','中国', NOW()-INTERVAL 600 DAY),
('Coach','美国', NOW()-INTERVAL 600 DAY), ('兰蔻','法国', NOW()-INTERVAL 600 DAY),
('雅诗兰黛','美国', NOW()-INTERVAL 600 DAY), ('SK-II','日本', NOW()-INTERVAL 600 DAY),
('飞利浦','荷兰', NOW()-INTERVAL 600 DAY), ('欧莱雅','法国', NOW()-INTERVAL 600 DAY),
('山姆','美国', NOW()-INTERVAL 600 DAY), ('蒙牛','中国', NOW()-INTERVAL 600 DAY),
('得力','中国', NOW()-INTERVAL 600 DAY), ('Kindle','美国', NOW()-INTERVAL 600 DAY),
('晨光','中国', NOW()-INTERVAL 600 DAY), ('迪卡侬','法国', NOW()-INTERVAL 600 DAY),
('Keep','中国', NOW()-INTERVAL 600 DAY), ('李宁','中国', NOW()-INTERVAL 600 DAY),
('探路者','中国', NOW()-INTERVAL 600 DAY), ('YONEX','日本', NOW()-INTERVAL 600 DAY),
('宜家','瑞典', NOW()-INTERVAL 600 DAY), ('全棉时代','中国', NOW()-INTERVAL 600 DAY),
('松下','日本', NOW()-INTERVAL 600 DAY), ('海澜','中国', NOW()-INTERVAL 600 DAY);

-- 回填 products.brand_id：商品名包含品牌名则关联；取最长匹配（避免短名误匹配）；
-- 匹配不到的（iPhone/AirPods/五常大米/三文鱼/书 等）保持 NULL —— 真实存在的白牌/无品牌商品
UPDATE products p
SET p.brand_id = (
    SELECT b.id FROM brand b
    WHERE p.name LIKE CONCAT('%', b.brand_name, '%')
    ORDER BY CHAR_LENGTH(b.brand_name) DESC
    LIMIT 1
);

-- 品类字典：8 个一级品类（cat_name 与 products.category 取值对齐）+ 若干二级
INSERT INTO category (parent_id, cat_name, cat_level, sort_order, gmt_create) VALUES
(0,'手机数码',1,1, NOW()-INTERVAL 600 DAY),
(0,'家用电器',1,2, NOW()-INTERVAL 600 DAY),
(0,'服饰鞋包',1,3, NOW()-INTERVAL 600 DAY),
(0,'美妆个护',1,4, NOW()-INTERVAL 600 DAY),
(0,'食品生鲜',1,5, NOW()-INTERVAL 600 DAY),
(0,'图书文教',1,6, NOW()-INTERVAL 600 DAY),
(0,'运动户外',1,7, NOW()-INTERVAL 600 DAY),
(0,'家居家装',1,8, NOW()-INTERVAL 600 DAY);
INSERT INTO category (parent_id, cat_name, cat_level, sort_order, gmt_create) VALUES
(1,'手机',2,1, NOW()-INTERVAL 600 DAY), (1,'耳机音频',2,2, NOW()-INTERVAL 600 DAY),
(2,'大家电',2,1, NOW()-INTERVAL 600 DAY), (2,'厨房电器',2,2, NOW()-INTERVAL 600 DAY),
(3,'男装',2,1, NOW()-INTERVAL 600 DAY), (3,'鞋靴箱包',2,2, NOW()-INTERVAL 600 DAY),
(4,'护肤',2,1, NOW()-INTERVAL 600 DAY), (5,'乳品',2,1, NOW()-INTERVAL 600 DAY),
(7,'健身器材',2,1, NOW()-INTERVAL 600 DAY), (8,'家纺',2,1, NOW()-INTERVAL 600 DAY);

-- 仓库：5 个大区
INSERT INTO warehouse (wh_name, region, gmt_create) VALUES
('华东中心仓','华东', NOW()-INTERVAL 600 DAY),
('华北中心仓','华北', NOW()-INTERVAL 600 DAY),
('华南中心仓','华南', NOW()-INTERVAL 600 DAY),
('华中中心仓','华中', NOW()-INTERVAL 600 DAY),
('西南中心仓','西南', NOW()-INTERVAL 600 DAY);

-- 库存：40 商品 × 5 仓（商品总库存需按 product_id 汇总多仓）
INSERT INTO inventory (product_id, warehouse_id, stock_qty, safety_stock, gmt_modified)
SELECT p.id, w.id,
    FLOOR(RAND(p.id * 100 + w.id) * 500),
    20 + FLOOR(RAND(p.id * 7 + w.id) * 60),
    NOW() - INTERVAL FLOOR(RAND(p.id * 3 + w.id) * 30) DAY
FROM products p CROSS JOIN warehouse w;

-- 商品评价：仅对已完成订单的明细抽 ~45% 生成，评分偏高（真实分布）
INSERT INTO product_review (product_id, user_id, order_id, rating, content, create_time)
SELECT product_id, user_id, order_id, rating,
    ELT(rating, '质量太差，申请退货', '和描述有点出入', '中规中矩，凑合用', '质量不错，比较满意', '非常好，五星好评推荐'),
    create_time
FROM (
    SELECT oi.product_id, o.user_id, o.id AS order_id,
        CASE
            WHEN RAND(oi.id * 7) < 0.55 THEN 5
            WHEN RAND(oi.id * 7) < 0.80 THEN 4
            WHEN RAND(oi.id * 7) < 0.92 THEN 3
            WHEN RAND(oi.id * 7) < 0.98 THEN 2
            ELSE 1
        END AS rating,
        o.order_time + INTERVAL 3 DAY + INTERVAL FLOOR(RAND(oi.id * 3) * 10) DAY AS create_time
    FROM order_items oi
    JOIN orders o ON o.id = oi.order_id
    WHERE o.status = 'completed' AND RAND(oi.id) < 0.45
) t;

-- ============================ 交易域 ============================

-- 支付流水：与订单 1:1。非取消单支付成功；取消单为 closed/failed。
-- 注意 payment.status 的枚举（success/failed/pending/closed）与 orders.status 完全不同。
INSERT INTO payment (order_id, pay_channel, pay_amount, pay_time, status)
SELECT o.id,
    ELT(1 + FLOOR(RAND(o.id * 31) * 4), 'alipay', 'wechat', 'unionpay', 'balance'),
    o.total_amount,
    o.order_time + INTERVAL (30 + FLOOR(RAND(o.id * 33) * 600)) SECOND,
    CASE WHEN o.status = 'cancelled'
         THEN ELT(1 + FLOOR(RAND(o.id * 37) * 2), 'closed', 'failed')
         ELSE 'success' END
FROM orders o;

-- 退款单：已退款订单各一条 refunded；另有少量 completed 订单发起过退款但未成功
-- （applied/approved/rejected）。refund.status 又是另一套枚举。
INSERT INTO refund (order_id, refund_amount, reason, apply_time, finish_time, status)
SELECT o.id, o.total_amount,
    ELT(1 + FLOOR(RAND(o.id * 41) * 5), '七天无理由退货', '商品质量问题', '拍错/多拍/不想要', '与描述不符', '发货太慢'),
    o.order_time + INTERVAL (1 + FLOOR(RAND(o.id * 43) * 5)) DAY,
    o.order_time + INTERVAL (3 + FLOOR(RAND(o.id * 43) * 5)) DAY,
    'refunded'
FROM orders o WHERE o.status = 'refunded';

INSERT INTO refund (order_id, refund_amount, reason, apply_time, finish_time, status)
SELECT o.id, ROUND(o.total_amount * (0.3 + RAND(o.id * 47) * 0.7), 2),
    ELT(1 + FLOOR(RAND(o.id * 49) * 3), '商品质量问题', '尺码不合适', '缺货'),
    o.order_time + INTERVAL (1 + FLOOR(RAND(o.id * 51) * 7)) DAY,
    NULL,
    ELT(1 + FLOOR(RAND(o.id * 53) * 3), 'applied', 'approved', 'rejected')
FROM orders o WHERE o.status = 'completed' AND RAND(o.id * 59) < 0.06;

-- 物流：已发货/已完成/已退款订单各一条。妥投=delivered，拒收=rejected。
-- 同一 RAND(o.id*69) 常量种子返回同值，保证 deliver_time 与 status 一致。
INSERT INTO shipment (order_id, carrier, tracking_no, ship_time, deliver_time, status)
SELECT o.id,
    ELT(1 + FLOOR(RAND(o.id * 61) * 5), '顺丰', '中通', '圆通', '京东物流', '韵达'),
    CONCAT('YD', LPAD(o.id, 12, '0')),
    o.order_time + INTERVAL (1 + FLOOR(RAND(o.id * 63) * 2)) DAY,
    CASE
        WHEN o.status = 'completed' THEN o.order_time + INTERVAL (3 + FLOOR(RAND(o.id * 67) * 4)) DAY
        WHEN o.status = 'refunded' AND RAND(o.id * 69) < 0.4 THEN NULL
        WHEN o.status = 'refunded' THEN o.order_time + INTERVAL (3 + FLOOR(RAND(o.id * 67) * 4)) DAY
        ELSE NULL
    END,
    CASE
        WHEN o.status = 'completed' THEN 'delivered'
        WHEN o.status = 'refunded' AND RAND(o.id * 69) < 0.4 THEN 'rejected'
        WHEN o.status = 'refunded' THEN 'delivered'
        ELSE 'shipped'
    END
FROM orders o WHERE o.status IN ('shipped', 'completed', 'refunded');

-- ============================ 营销域 ============================

-- 优惠券模板：8 张，有效期相对当前日期
INSERT INTO coupon (coupon_name, coupon_type, threshold, discount_value, valid_from, valid_to, gmt_create) VALUES
('新人专享满100减10','full_reduction', 100,  10.00, CURDATE()-INTERVAL 180 DAY, CURDATE()+INTERVAL 180 DAY, NOW()-INTERVAL 200 DAY),
('全场满199减30',    'full_reduction', 199,  30.00, CURDATE()-INTERVAL 120 DAY, CURDATE()+INTERVAL 120 DAY, NOW()-INTERVAL 140 DAY),
('全场满299减50',    'full_reduction', 299,  50.00, CURDATE()-INTERVAL 90 DAY,  CURDATE()+INTERVAL 90 DAY,  NOW()-INTERVAL 100 DAY),
('大额满599减100',   'full_reduction', 599, 100.00, CURDATE()-INTERVAL 60 DAY,  CURDATE()+INTERVAL 60 DAY,  NOW()-INTERVAL 70 DAY),
('全场9折券',        'discount',         0,   0.90, CURDATE()-INTERVAL 30 DAY,  CURDATE()+INTERVAL 30 DAY,  NOW()-INTERVAL 40 DAY),
('会员专享8.5折',    'discount',         0,   0.85, CURDATE()-INTERVAL 45 DAY,  CURDATE()+INTERVAL 45 DAY,  NOW()-INTERVAL 50 DAY),
('数码满1000减120',  'full_reduction',1000, 120.00, CURDATE()-INTERVAL 30 DAY,  CURDATE()+INTERVAL 30 DAY,  NOW()-INTERVAL 35 DAY),
('生鲜满99减15',     'full_reduction',  99,  15.00, CURDATE()-INTERVAL 15 DAY,  CURDATE()+INTERVAL 45 DAY,  NOW()-INTERVAL 20 DAY);

-- 用户领券：1200 条。核销=used（带 order_id 与 use_time）；同一 RAND(n*7) 种子保证三者一致。
INSERT INTO user_coupon (user_id, coupon_id, order_id, status, receive_time, use_time)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1200
)
SELECT
    1 + FLOOR(RAND(n * 3) * 300),
    1 + FLOOR(RAND(n * 5) * 8),
    CASE WHEN RAND(n * 7) < 0.40 THEN 1 + FLOOR(RAND(n * 9) * 3000) ELSE NULL END,
    CASE WHEN RAND(n * 7) < 0.40 THEN 'used'
         WHEN RAND(n * 7) < 0.75 THEN 'unused'
         ELSE 'expired' END,
    NOW() - INTERVAL FLOOR(RAND(n * 11) * 200) DAY,
    CASE WHEN RAND(n * 7) < 0.40
         THEN NOW() - INTERVAL FLOOR(RAND(n * 11) * 200) DAY + INTERVAL FLOOR(RAND(n * 13) * 15) DAY
         ELSE NULL END
FROM seq;

-- 营销活动：6 个，部分已结束、部分进行中
INSERT INTO promotion (campaign_name, promo_type, start_time, end_time, gmt_create) VALUES
('双11全球狂欢节', 'full_reduction', NOW()-INTERVAL 240 DAY, NOW()-INTERVAL 236 DAY, NOW()-INTERVAL 260 DAY),
('618年中大促',   'seckill',        NOW()-INTERVAL 90 DAY,  NOW()-INTERVAL 84 DAY,  NOW()-INTERVAL 110 DAY),
('年货节',        'discount',       NOW()-INTERVAL 30 DAY,  NOW()-INTERVAL 20 DAY,  NOW()-INTERVAL 45 DAY),
('开学季焕新',    'full_reduction', NOW()-INTERVAL 12 DAY,  NOW()-INTERVAL 5 DAY,   NOW()-INTERVAL 20 DAY),
('周年庆大促',    'discount',       NOW()-INTERVAL 3 DAY,   NOW()+INTERVAL 4 DAY,   NOW()-INTERVAL 10 DAY),
('秋季运动季',    'seckill',        NOW()+INTERVAL 5 DAY,   NOW()+INTERVAL 12 DAY,  NOW()-INTERVAL 2 DAY);

-- ============================ 遗留扩展表 ============================

-- 订单扩展（约 60% 订单有）：拼音字段 beizhu/youhui_jine/yhq_id
INSERT INTO t_order_ext (order_id, beizhu, fapiao_type, yhq_id, youhui_jine, gmt_create)
SELECT o.id,
    CASE WHEN RAND(o.id * 73) < 0.3
         THEN ELT(1 + FLOOR(RAND(o.id * 75) * 4), '尽快发货', '工作日送达', '放快递柜', '需要开发票') END,
    ELT(1 + FLOOR(RAND(o.id * 77) * 3), 0, 1, 2),
    CASE WHEN RAND(o.id * 79) < 0.35 THEN 1 + FLOOR(RAND(o.id * 81) * 1200) ELSE NULL END,
    ROUND(RAND(o.id * 83) * 80, 2),
    o.order_time + INTERVAL FLOOR(RAND(o.id * 85) * 60) SECOND
FROM orders o WHERE RAND(o.id * 71) < 0.6;
