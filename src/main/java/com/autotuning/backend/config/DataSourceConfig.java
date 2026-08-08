package com.autotuning.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Constroi o DataSource a partir de {@code app.database-url} (formato libpq),
 * convertido via {@link DatabaseUrlParser}. Ver comentario la sobre por que
 * isso nao pode ser feito via {@code spring.datasource.url} diretamente.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(@Value("${app.database-url}") String databaseUrl) {
        DatabaseUrlParser.Parsed parsed = DatabaseUrlParser.parse(databaseUrl);
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(parsed.jdbcUrl());
        ds.setUsername(parsed.username());
        ds.setPassword(parsed.password());
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
