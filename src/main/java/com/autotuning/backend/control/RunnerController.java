package com.autotuning.backend.control;

import com.autotuning.backend.config.PipelinePathsConfig;
import com.autotuning.backend.process.ManagedProcessKind;
import com.autotuning.backend.process.ProcessSupervisor;
import com.autotuning.backend.queue.TaskDao;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RunnerController {

    private final ProcessSupervisor processSupervisor;
    private final PipelinePathsConfig paths;
    private final TaskDao taskDao;

    public RunnerController(ProcessSupervisor processSupervisor, PipelinePathsConfig paths, TaskDao taskDao) {
        this.processSupervisor = processSupervisor;
        this.paths = paths;
        this.taskDao = taskDao;
    }

    /** Retorna se o runner esta em execucao. */
    @GetMapping("/api/runner/status")
    public Map<String, Object> status() {
        boolean running = processSupervisor.isRunning(ManagedProcessKind.RUNNER);
        Map<String, Object> body = new HashMap<>();
        body.put("running", running);
        body.put("pid", running ? processSupervisor.pid(ManagedProcessKind.RUNNER) : null);
        return body;
    }

    /** Inicia a execucao da fila de benchmarks. */
    @PostMapping("/api/runner/start")
    public ResponseEntity<Map<String, Object>> start() {
        if (processSupervisor.isRunning(ManagedProcessKind.RUNNER)) {
            return conflict("Runner já está em execução.");
        }
        if (processSupervisor.isRunning(ManagedProcessKind.GENERATOR)) {
            return conflict("O gerador está em execução. Aguarde terminar.");
        }
        if (taskDao.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "Fila não encontrada. Gere as configurações primeiro."));
        }
        List<String> cmd = List.of(paths.pythonExecutable(), paths.cliScript("run").toString());
        try {
            long pid = processSupervisor.start(ManagedProcessKind.RUNNER, cmd, paths.logFile("runner"));
            return ResponseEntity.ok(Map.of("status", "started", "pid", pid));
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Para o runner. A task atual volta para pending na proxima execucao. */
    @PostMapping("/api/runner/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        if (!processSupervisor.isRunning(ManagedProcessKind.RUNNER)) {
            return conflict("Runner não está em execução.");
        }
        try {
            processSupervisor.stop(ManagedProcessKind.RUNNER);
            return ResponseEntity.ok(Map.of("status", "stopping"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("error", message));
    }
}
