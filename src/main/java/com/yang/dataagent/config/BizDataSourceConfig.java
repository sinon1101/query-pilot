package com.yang.dataagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 业务库（biz）只读数据源。
 * <p>
 * 与应用主数据源（dataagent 库，JPA 用）完全隔离：连接使用独立的 agent_ro 账号，
 * 该账号在数据库层面只有 biz 库的 SELECT 权限，连接本身也标记为 read-only。
 * 这是 execute_sql 工具 SQL 校验之外的第二层防线。
 */
@Configuration
public class BizDataSourceConfig {

    @Bean
    public JdbcTemplate bizJdbcTemplate(AgentProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.bizDatasource().url());
        config.setUsername(props.bizDatasource().username());
        config.setPassword(props.bizDatasource().password());
        config.setReadOnly(true);
        config.setMaximumPoolSize(4);
        config.setPoolName("biz-readonly");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(new HikariDataSource(config));
        jdbcTemplate.setQueryTimeout(props.sql().timeoutSeconds());
        // 多取一行，供工具判断结果是否被截断
        jdbcTemplate.setMaxRows(props.sql().maxRows() + 1);
        return jdbcTemplate;
    }
}
