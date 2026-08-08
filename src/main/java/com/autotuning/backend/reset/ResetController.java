package com.autotuning.backend.reset;

import com.autotuning.backend.config.PipelinePathsConfig;
import com.autotuning.backend.docker.DockerService;
import com.autotuning.backend.process.ManagedProcessKind;
import com.autotuning.backend.process.ProcessSupervisor;
import com.autotuning.backend.queue.TaskDao;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResetController {

    private final ProcessSupervisor processSupervisor;
    private final PipelinePathsConfig paths;
    private final DockerService dockerService;
    private final TaskDao taskDao;

    public ResetController(
            ProcessSupervisor processSupervisor,
            PipelinePathsConfig paths,
            DockerService dockerService,
            TaskDao taskDao) {
        this.processSupervisor = processSupervisor;
        this.paths = paths;
        this.dockerService = dockerService;
        this.taskDao = taskDao;
    }

    /** Remove a fila, resultados (Postgres), logs e containers de benchmark. */
    @PostMapping("/api/reset")
    public ResponseEntity<Map<String, Object>> resetAll() {
        if (processSupervisor.isRunning(ManagedProcessKind.GENERATOR)) {
            return conflict("Gerador está em execução. Pare antes de resetar.");
        }
        if (processSupervisor.isRunning(ManagedProcessKind.PREPARE)) {
            return conflict("Prepare está em execução. Pare antes de resetar.");
        }
        if (processSupervisor.isRunning(ManagedProcessKind.RUNNER) || Files.exists(paths.runnerLock())) {
            return conflict("Fila em execução. Aguarde terminar antes de resetar.");
        }

        List<String> removed = new ArrayList<>();

        removed.addAll(dockerService.removeBenchmarkContainers());

        taskDao.truncateAll();
        removed.add("tasks + task_results (Postgres)");

        for (String name : List.of("generate", "prepare", "runner")) {
            Path log = paths.logFile(name);
            if (Files.exists(log)) {
                try {
                    Files.delete(log);
                    removed.add(log.getFileName().toString());
                } catch (IOException ignored) {
                }
            }
        }

        return ResponseEntity.ok(Map.of("status", "ok", "removed", removed));
    }

    private ResponseEntity<Map<String, Object>> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("error", message));
    }
}
