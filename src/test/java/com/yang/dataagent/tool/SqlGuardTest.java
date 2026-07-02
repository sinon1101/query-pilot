package com.yang.dataagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM products",
            "select id, name from products where category = '手机数码' order by price desc limit 10",
            "SELECT p.category, SUM(oi.quantity * oi.unit_price) AS amt FROM order_items oi JOIN products p ON p.id = oi.product_id GROUP BY p.category",
            "WITH t AS (SELECT user_id, COUNT(*) c FROM orders GROUP BY user_id) SELECT * FROM t WHERE c > 5",
            "SELECT * FROM orders LIMIT 10 OFFSET 20",   // OFFSET 不应被 \\bset\\b 误杀
            "SELECT updated_at FROM (SELECT NOW() AS updated_at) x",  // updated_at 不应被 \\bupdate\\b 误杀
            "SELECT COUNT(*) FROM users;"                // 允许结尾单个分号
    })
    void allowsReadOnlyQueries(String sql) {
        assertThatCode(() -> SqlGuard.validate(sql)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE products SET price = 1",
            "DELETE FROM orders",
            "INSERT INTO users VALUES (1)",
            "DROP TABLE orders",
            "TRUNCATE TABLE orders",
            "CREATE TABLE evil (id INT)",
            "GRANT ALL ON *.* TO 'x'@'%'",
            "SELECT * FROM users; DROP TABLE users",             // 多语句
            "SELECT * FROM users -- comment",                    // 注释
            "SELECT /* hidden */ * FROM users",
            "SELECT * FROM users INTO OUTFILE '/tmp/x'",         // 文件写出
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT SLEEP(100)",                                  // DoS
            "SELECT BENCHMARK(100000000, MD5('x'))",
            "SHOW TABLES",                                        // 非 SELECT/WITH 开头
            "EXPLAIN SELECT * FROM users",
            "   ",
            "REPLACE INTO users VALUES (1)"
    })
    void rejectsDangerousStatements(String sql) {
        assertThatThrownBy(() -> SqlGuard.validate(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
