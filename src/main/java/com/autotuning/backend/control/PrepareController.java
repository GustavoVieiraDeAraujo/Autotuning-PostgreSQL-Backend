package com.autotuning.backend.control;

import com.autotuning.backend.config.PipelinePathsConfig;
import com.autotuning.backend.process.ManagedProcessKind;
import com.autotuning.backend.process.ProcessSupervisor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PrepareController {

    private final ProcessSupervisor processSupervisor;
    private final PipelinePathsConfig paths;

    public PrepareController(ProcessSupervisor processSupervisor, PipelinePathsConfig paths) {
        this.processSupervisor = processSupervisor;
        this.paths = paths;
    }

    /** Retorna se o prepare de imagens esta em execucao. */
    @GetMapping("/api/prepare/status")
    public Map<String, Object> status() {
        boolean running = processSupervisor.isRunning(ManagedProcessKind.PREPARE);
        Map<String, Object> body = new HashMap<>();
        body.put("running", running);
        body.put("pid", running ? processSupervisor.pid(ManagedProcessKind.PREPARE) : null);
        return body;
    }

    /** Inicia a construcao das imagens Docker necessarias. */
    @PostMapping("/api/prepare/start")
    public ResponseEntity<Map<String, Object>> start(@RequestParam(defaultValue = "false") boolean force) {
        if (processSupervisor.isRunning(ManagedProcessKind.PREPARE)) {
            return conflict("Prepare já está em execução.");
        }
        if (processSupervisor.isRunning(ManagedProcessKind.RUNNER)) {
            return conflict("O runner está em execução. Aguarde terminar.");
        }
        List<String> cmd = new ArrayList<>(List.of(
                paths.pythonExecutable(), paths.cliScript("prepare").toString()));
        if (force) {
            cmd.add("--force");
        }
        try {
            long pid = processSupervisor.start(ManagedProcessKind.PREPARE, cmd, paths.logFile("prepare"));
            return ResponseEntity.ok(Map.of("status", "started", "pid", pid));
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Interrompe o prepare. */
    @PostMapping("/api/prepare/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        if (!processSupervisor.isRunning(ManagedProcessKind.PREPARE)) {
            return conflict("Prepare não está em execução.");
        }
        try {
            processSupervisor.stop(ManagedProcessKind.PREPARE);
            return ResponseEntity.ok(Map.of("status", "stopping"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("error", message));
    }
}
