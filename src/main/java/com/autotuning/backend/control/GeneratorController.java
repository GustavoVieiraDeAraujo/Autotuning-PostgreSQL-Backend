package com.autotuning.backend.control;

import com.autotuning.backend.config.PipelinePathsConfig;
import com.autotuning.backend.process.ManagedProcessKind;
import com.autotuning.backend.process.ProcessSupervisor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GeneratorController {

    private final ProcessSupervisor processSupervisor;
    private final PipelinePathsConfig paths;

    public GeneratorController(ProcessSupervisor processSupervisor, PipelinePathsConfig paths) {
        this.processSupervisor = processSupervisor;
        this.paths = paths;
    }

    /** Retorna se o gerador de configuracoes esta em execucao. */
    @GetMapping("/api/generator/status")
    public Map<String, Object> status() {
        boolean running = processSupervisor.isRunning(ManagedProcessKind.GENERATOR);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("running", running);
        body.put("pid", running ? processSupervisor.pid(ManagedProcessKind.GENERATOR) : null);
        return body;
    }

    /**
     * Inicia a geracao de configuracoes como subprocesso.
     *
     * @param nConfigs numero de configs por combinacao (padrao 51; use 3 para validacao rapida)
     * @param seed     semente LHS para reprodutibilidade (padrao ausente)
     */
    @PostMapping("/api/generator/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestParam(defaultValue = "51") int nConfigs,
            @RequestParam(required = false) Integer seed) {
        if (nConfigs < 3 || nConfigs % 3 != 0) {
            return ResponseEntity.status(422).body(Map.of(
                    "error", "n_configs deve ser múltiplo de 3 e ≥ 3; recebido: " + nConfigs));
        }
        if (processSupervisor.isRunning(ManagedProcessKind.GENERATOR)) {
            return conflict("Gerador já está em execução.");
        }
        if (processSupervisor.isRunning(ManagedProcessKind.RUNNER)) {
            return conflict("O runner está em execução. Aguarde terminar.");
        }
        List<String> cmd = new ArrayList<>(List.of(
                paths.pythonExecutable(), paths.cliScript("generate").toString(),
                "--n-configs", String.valueOf(nConfigs)));
        if (seed != null) {
            cmd.add("--seed");
            cmd.add(String.valueOf(seed));
        }
        try {
            long pid = processSupervisor.start(ManagedProcessKind.GENERATOR, cmd, paths.logFile("generate"));
            return ResponseEntity.ok(Map.of("status", "started", "pid", pid, "n_configs", nConfigs));
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Interrompe o gerador. */
    @PostMapping("/api/generator/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        if (!processSupervisor.isRunning(ManagedProcessKind.GENERATOR)) {
            return conflict("Gerador não está em execução.");
        }
        try {
            processSupervisor.stop(ManagedProcessKind.GENERATOR);
            return ResponseEntity.ok(Map.of("status", "stopping"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("error", message));
    }
}
