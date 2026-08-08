package com.autotuning.backend.results;

import com.autotuning.backend.db.JsonbUtil;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ResultsDao {

    private static final RowMapper<Map<String, Object>> FILE_MAPPER = (rs, rowNum) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", rs.getLong("task_id"));
        m.put("tier", rs.getString("tier"));
        m.put("combo", rs.getString("combination"));
        return m;
    };

    private static final RowMapper<Map<String, Object>> RESULT_MAPPER = (rs, rowNum) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", rs.getLong("task_id"));
        m.put("tier", rs.getString("tier"));
        m.put("combination", rs.getString("combination"));
        m.put("status", rs.getString("status"));
        m.put("abandoned_reason", rs.getString("abandoned_reason"));
        m.put("error", rs.getString("error"));
        m.put("pg_config", JsonbUtil.parse(rs, "pg_config"));
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        m.put("started_at", started != null ? started.toInstant().toString() : null);
        m.put("finished_at", finished != null ? finished.toInstant().toString() : null);
        Object durationS = rs.getObject("duration_s");
        m.put("duration_s", durationS);
        m.put("tpc_h", JsonbUtil.parse(rs, "tpc_h"));
        m.put("tpc_ds", JsonbUtil.parse(rs, "tpc_ds"));
        m.put("hw_metrics", JsonbUtil.parse(rs, "hw_metrics"));
        return m;
    };

    private final JdbcTemplate jdbc;

    public ResultsDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listResultRows() {
        return jdbc.query(
                """
                SELECT t.id AS task_id, t.tier, t.combination
                FROM tasks t JOIN task_results r ON r.task_id = t.id
                ORDER BY t.id
                """,
                FILE_MAPPER);
    }

    public Map<String, Object> getResult(String tier, String combo, long taskId) {
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT t.id AS task_id, t.tier, t.combination, t.status,
                       t.abandoned_reason, t.error, t.config AS pg_config,
                       r.started_at, r.finished_at, r.duration_s,
                       r.tpc_h, r.tpc_ds, r.hw_metrics
                FROM tasks t JOIN task_results r ON r.task_id = t.id
                WHERE t.id = ? AND t.tier = ? AND t.combination = ?
                """,
                RESULT_MAPPER, taskId, tier, combo);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
