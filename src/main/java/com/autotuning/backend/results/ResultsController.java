package com.autotuning.backend.results;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResultsController {

    private final ResultsDao resultsDao;

    public ResultsController(ResultsDao resultsDao) {
        this.resultsDao = resultsDao;
    }

    /** Lista todas as tarefas com resultado disponivel (Postgres). */
    @GetMapping("/api/results/list")
    public Map<String, Object> listResults() {
        List<Map<String, Object>> files = resultsDao.listResultRows();
        return Map.of("files", files);
    }

    /**
     * Retorna o resultado completo de uma tarefa especifica (Postgres).
     *
     * <p>Junta {@code tasks} (metadados/config/status) e {@code task_results}
     * (conteudo do benchmark) num unico objeto achatado: mesmo formato que
     * o antigo arquivo {@code task_{id}.json} tinha, so muda como a URL e
     * montada (por task_id, nao mais por nome de arquivo).
     */
    @GetMapping("/api/results/{tier}/{combo}/{taskId}")
    public ResponseEntity<Map<String, Object>> getResult(
            @PathVariable String tier,
            @PathVariable String combo,
            @PathVariable long taskId) {
        Map<String, Object> result = resultsDao.getResult(tier, combo, taskId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }
}
