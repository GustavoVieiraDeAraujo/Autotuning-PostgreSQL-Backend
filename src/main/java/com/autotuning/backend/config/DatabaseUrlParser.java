package com.autotuning.backend.config;

import java.net.URI;

/**
 * Converte uma connection string no formato libpq
 * ({@code postgresql://user:pass@host:port/db}) - a mesma variavel de
 * ambiente DATABASE_URL que o repositorio Pipeline (Python/psycopg) usa -
 * para os componentes que o driver JDBC do Postgres precisa
 * ({@code jdbc:postgresql://host:port/db} + usuario + senha separados).
 *
 * <p>Ponto que falha silenciosamente se esquecido: {@code spring.datasource.url}
 * do Spring Boot espera o formato JDBC, nao o libpq - por isso o DataSource
 * e construido manualmente em {@link DataSourceConfig} a partir deste parser,
 * em vez de deixar o Spring Boot autoconfigurar a partir de DATABASE_URL direto.
 */
public final class DatabaseUrlParser {

    public record Parsed(String jdbcUrl, String username, String password) {}

    private DatabaseUrlParser() {}

    public static Parsed parse(String libpqUrl) {
        URI uri = URI.create(libpqUrl);
        String userInfo = uri.getUserInfo();
        String username = null;
        String password = null;
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            username = parts[0];
            password = parts.length > 1 ? parts[1] : "";
        }
        int port = uri.getPort() != -1 ? uri.getPort() : 5432;
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath();
        return new Parsed(jdbcUrl, username, password);
    }
}
