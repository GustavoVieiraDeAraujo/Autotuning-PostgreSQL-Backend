package com.autotuning.backend.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Caminhos derivados de {@code app.pipeline-root} (variavel de ambiente
 * PIPELINE_ROOT) - onde ficam os scripts cli/*.py do repositorio Pipeline,
 * os logs de execucao e o lock file do runner.
 *
 * <p>Nunca hardcoda nada relativo ao monorepo original - tudo relativo a
 * PIPELINE_ROOT, exatamente como o backend Python fazia.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class PipelinePathsConfig {

    private String pipelineRoot;

    public void setPipelineRoot(String pipelineRoot) {
        this.pipelineRoot = pipelineRoot;
    }

    public Path getRoot() {
        return Path.of(pipelineRoot).toAbsolutePath().normalize();
    }

    public Path logFile(String name) {
        return getRoot().resolve("logs").resolve(name + ".log");
    }

    public Path runnerLock() {
        return getRoot().resolve("data").resolve(".runner.lock");
    }

    public Path cliScript(String name) {
        return getRoot().resolve("cli").resolve(name + ".py");
    }

    /**
     * Resolve o interpretador Python a usar: prioriza a venv da Pipeline
     * ({@code PIPELINE_ROOT/.venv/bin/python}, criada por {@code make setup}),
     * caindo para {@code python3} do PATH se a venv nao existir. Equivalente
     * Java de {@code sys.executable} usado pelo backend Python original
     * (que, rodando dentro da propria venv, sempre resolvia pra ela mesma).
     */
    public String pythonExecutable() {
        Path venvPython = getRoot().resolve(".venv").resolve("bin").resolve("python");
        return java.nio.file.Files.isExecutable(venvPython) ? venvPython.toString() : "python3";
    }
}
