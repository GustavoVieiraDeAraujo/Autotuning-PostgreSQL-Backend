package com.autotuning.backend.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Le uma coluna JSONB via JDBC e converte pra uma arvore de objetos Java
 * (Map/List/primitivos) em vez de deixar como texto/PGobject cru — assim,
 * quando o Spring serializa a resposta de volta pra JSON, o conteudo sai
 * como JSON aninhado de verdade, nao como uma string JSON escapada.
 */
public final class JsonbUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonbUtil() {}

    public static Object parse(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, Object.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("JSONB invalido na coluna " + column, e);
        }
    }
}
