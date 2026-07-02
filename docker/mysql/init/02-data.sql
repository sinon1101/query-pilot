-- 电商样例数据：日期相对 NOW() 生成，保证任何时候初始化，"上个月/近7天"这类问题都有数据可查
SET NAMES utf8mb4;
USE biz;

-- 商品：8 个品类，40 个 SKU
INSERT INTO products (name, category, price, created_at) VALUES
('iPhone 15 Pro 256G',        '手机数码', 8999.00, NOW() - INTERVAL 400 DAY),
('小米14 Ultra',              '手机数码', 6499.00, NOW() - INTERVAL 380 DAY),
('华为 Mate 60 Pro',          '手机数码', 6999.00, NOW() - INTERVAL 350 DAY),
('AirPods Pro 2',             '手机数码', 1899.00, NOW() - INTERVAL 300 DAY),
('Redmi Note 13',             '手机数码',  1199.00, NOW() - INTERVAL 200 DAY),
('海尔 465L 冰箱',            '家用电器', 3299.00, NOW() - INTERVAL 420 DAY),
('美的 1.5匹 变频空调',       '家用电器', 2599.00, NOW() - INTERVAL 400 DAY),
('戴森 V12 吸尘器',           '家用电器', 3490.00, NOW() - INTERVAL 360 DAY),
('小米电视 65寸',             '家用电器', 2799.00, NOW() - INTERVAL 320 DAY),
('九阳破壁机',                '家用电器',  499.00, NOW() - INTERVAL 250 DAY),
('优衣库 摇粒绒外套',         '服饰鞋包',  199.00, NOW() - INTERVAL 300 DAY),
('耐克 Air Force 1',          '服饰鞋包',  799.00, NOW() - INTERVAL 280 DAY),
('阿迪达斯 三叶草卫衣',       '服饰鞋包',  459.00, NOW() - INTERVAL 260 DAY),
('波司登 羽绒服',             '服饰鞋包', 1099.00, NOW() - INTERVAL 240 DAY),
('Coach 单肩包',              '服饰鞋包', 2350.00, NOW() - INTERVAL 220 DAY),
('兰蔻 小黑瓶精华 50ml',      '美妆个护',  760.00, NOW() - INTERVAL 350 DAY),
('雅诗兰黛 沁水粉底液',       '美妆个护',  420.00, NOW() - INTERVAL 330 DAY),
('SK-II 神仙水 230ml',        '美妆个护', 1590.00, NOW() - INTERVAL 310 DAY),
('飞利浦 电动牙刷',           '美妆个护',  399.00, NOW() - INTERVAL 290 DAY),
('欧莱雅 男士洗面奶',         '美妆个护',   59.90, NOW() - INTERVAL 270 DAY),
('五常大米 10kg',             '食品生鲜',   89.90, NOW() - INTERVAL 400 DAY),
('挪威三文鱼刺身 300g',       '食品生鲜',  128.00, NOW() - INTERVAL 200 DAY),
('山姆 混合坚果 1kg',         '食品生鲜',  109.00, NOW() - INTERVAL 180 DAY),
('蒙牛 特仑苏纯牛奶 12盒',    '食品生鲜',   69.90, NOW() - INTERVAL 160 DAY),
('阳澄湖大闸蟹礼盒',          '食品生鲜',  388.00, NOW() - INTERVAL 140 DAY),
('《百年孤独》精装版',        '图书文教',   45.00, NOW() - INTERVAL 380 DAY),
('《三体》全集',              '图书文教',   93.00, NOW() - INTERVAL 360 DAY),
('得力 文具礼盒套装',         '图书文教',   79.00, NOW() - INTERVAL 340 DAY),
('Kindle Paperwhite',         '图书文教',  998.00, NOW() - INTERVAL 320 DAY),
('晨光 中性笔 60支装',        '图书文教',   29.90, NOW() - INTERVAL 300 DAY),
('迪卡侬 公路自行车',         '运动户外', 2999.00, NOW() - INTERVAL 350 DAY),
('Keep 智能跳绳',             '运动户外',  169.00, NOW() - INTERVAL 300 DAY),
('李宁 羽毛球拍套装',         '运动户外',  359.00, NOW() - INTERVAL 280 DAY),
('探路者 帐篷 3-4人',         '运动户外',  499.00, NOW() - INTERVAL 260 DAY),
('YONEX 运动毛巾',            '运动户外',   45.00, NOW() - INTERVAL 240 DAY),
('宜家 波昂 扶手椅',          '家居家装',  599.00, NOW() - INTERVAL 400 DAY),
('全棉时代 四件套',           '家居家装',  469.00, NOW() - INTERVAL 350 DAY),
('小米 智能门锁',             '家居家装',  899.00, NOW() - INTERVAL 300 DAY),
('松下 护眼台灯',             '家居家装',  429.00, NOW() - INTERVAL 250 DAY),
('海澜优选 记忆棉枕头',       '家居家装',  139.00, NOW() - INTERVAL 200 DAY);

-- 用户：300 个，注册时间散布在近两年
SET SESSION cte_max_recursion_depth = 20000;

INSERT INTO users (username, city, gender, age, created_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 300
)
SELECT
    CONCAT('user_', LPAD(n, 4, '0')),
    ELT(1 + FLOOR(RAND(n) * 10), '北京','上海','广州','深圳','杭州','成都','武汉','南京','西安','重庆'),
    IF(RAND(n * 7) < 0.52, 'M', 'F'),
    18 + FLOOR(RAND(n * 13) * 42),
    NOW() - INTERVAL FLOOR(RAND(n * 17) * 730) DAY
FROM seq;

-- 订单：3000 单，散布在近 365 天；状态按真实比例加权
INSERT INTO orders (user_id, order_time, status, total_amount)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 3000
)
SELECT
    1 + FLOOR(RAND(n) * 300),
    NOW() - INTERVAL FLOOR(RAND(n * 3) * 365) DAY - INTERVAL FLOOR(RAND(n * 5) * 86400) SECOND,
    CASE
        WHEN RAND(n * 11) < 0.55 THEN 'completed'
        WHEN RAND(n * 11) < 0.75 THEN 'shipped'
        WHEN RAND(n * 11) < 0.88 THEN 'paid'
        WHEN RAND(n * 11) < 0.95 THEN 'cancelled'
        ELSE 'refunded'
    END,
    0
FROM seq;

-- 订单明细：每单 1~3 条，成交价在标价上下小幅浮动（模拟促销）
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 6000
)
SELECT
    1 + FLOOR(RAND(n * 2) * 3000),
    p.id,
    1 + FLOOR(RAND(n * 19) * 3),
    ROUND(p.price * (0.85 + RAND(n * 23) * 0.15), 2)
FROM seq
JOIN products p ON p.id = 1 + FLOOR(RAND(n * 29) * 40);

-- 没有明细的订单补一条，保证每单至少一件商品
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
SELECT o.id, p.id, 1, p.price
FROM orders o
JOIN products p ON p.id = 1 + (o.id % 40)
WHERE NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id);

-- 回填订单总金额
UPDATE orders o
JOIN (
    SELECT order_id, SUM(quantity * unit_price) AS amt
    FROM order_items GROUP BY order_id
) s ON s.order_id = o.id
SET o.total_amount = s.amt;
