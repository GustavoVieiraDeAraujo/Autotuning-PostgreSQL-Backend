package com.autotuning.backend.queue;

import com.autotuning.backend.db.JsonbUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Acesso a tabela {@code tasks} (ver db/schema.sql, repositorio Pipeline):
 * mesmo SQL de mao que o backend Python (psycopg) ja usava, agora via
 * {@link JdbcTemplate}.
 */
@Repository
public class TaskDao {

    private static final String SELECT_COLUMNS = """
            id, combination, tier, config, repetition, status, retry_count,
            abandoned_reason, error, result_summary AS result
            """;

    private static final RowMapper<Map<String, Object>> TASK_MAPPER = (rs, rowNum) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("combination", rs.getString("combination"));
        m.put("tier", rs.getString("tier"));
        m.put("config", JsonbUtil.parse(rs, "config"));
        m.put("repetition", rs.getInt("repetition"));
        m.put("status", rs.getString("status"));
        m.put("retry_count", rs.getInt("retry_count"));
        m.put("abandoned_reason", rs.getString("abandoned_reason"));
        m.put("error", rs.getString("error"));
        m.put("result", JsonbUtil.parse(rs, "result"));
        return m;
    };

    private final JdbcTemplate jdbc;

    public TaskDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listTasks() {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM tasks ORDER BY id", TASK_MAPPER);
    }

    public boolean isEmpty() {
        Boolean result = jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM tasks)", Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public void truncateAll() {
        jdbc.execute("TRUNCATE tasks RESTART IDENTITY CASCADE");
    }
}
