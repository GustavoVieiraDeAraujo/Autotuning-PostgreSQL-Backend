package com.autotuning.backend.process;

import com.autotuning.backend.config.PipelinePathsConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * Controla os 3 processos de longa duracao spawnados na Pipeline (Python):
 * generator (cli/generate.py), prepare (cli/prepare.py) e runner (cli/run.py).
 *
 * <p>Equivalente Java dos globals {@code subprocess.Popen|None} do backend
 * Python original, mas com uma diferenca de corretude deliberada: o Spring
 * MVC atende requisicoes em multiplas threads (o FastAPI original era
 * single-threaded via asyncio), entao {@link #start} usa um lock por
 * "kind" para impedir que duas chamadas quase simultaneas ao mesmo recurso
 * (ex: dois cliques rapidos em "Gerar") disparem dois processos — uma raca
 * que nao existia no modelo single-thread original mas passa a existir aqui
 * se nao for tratada.
 */
@Service
public class ProcessSupervisor {

    private final PipelinePathsConfig paths;
    private final Map<ManagedProcessKind, Process> processes = new EnumMap<>(ManagedProcessKind.class);
    private final Map<ManagedProcessKind, Lock> locks = new EnumMap<>(ManagedProcessKind.class);

    public ProcessSupervisor(PipelinePathsConfig paths) {
        this.paths = paths;
        for (ManagedProcessKind kind : ManagedProcessKind.values()) {
            locks.put(kind, new ReentrantLock());
        }
    }

    public boolean isRunning(ManagedProcessKind kind) {
        synchronized (processes) {
            Process p = processes.get(kind);
            return p != null && p.isAlive();
        }
    }

    public Long pid(ManagedProcessKind kind) {
        synchronized (processes) {
            Process p = processes.get(kind);
            return (p != null && p.isAlive()) ? p.pid() : null;
        }
    }

    /**
     * Inicia um processo para o "kind" dado, se ele nao ja estiver rodando.
     *
     * @return o PID do processo iniciado
     * @throws IllegalStateException se ja estiver rodando
     */
    public long start(ManagedProcessKind kind, List<String> command, Path logFile) throws IOException {
        Lock lock = locks.get(kind);
        lock.lock();
        try {
            if (isRunning(kind)) {
                throw new IllegalStateException(kind + " ja esta em execucao.");
            }
            logFile.getParent().toFile().mkdirs();
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(paths.getRoot().toFile())
                    // Redirect.DISCARD so vale pra saida (stdout/stderr) — pra
                    // stdin, o equivalente de stdin=DEVNULL do Python e ler de
                    // /dev/null diretamente (EOF imediato).
                    .redirectInput(new java.io.File("/dev/null"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            Process proc = pb.start();
            synchronized (processes) {
                processes.put(kind, proc);
            }
            return proc.pid();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Para o processo do "kind" dado enviando SIGINT (nao SIGTERM).
     *
     * <p>{@code Process.destroy()} do Java manda SIGTERM, que os scripts
     * cli/*.py da Pipeline nao tratam especificamente (so instalam handler
     * pra SIGINT) — usar destroy() pularia o encerramento gracioso (tarefa
     * atual volta pra pending) e dependeria so da reconciliacao de lease
     * da fila. Por isso aqui manda-se SIGINT explicitamente via `kill -INT`.
     *
     * @throws IllegalStateException se nao estiver rodando
     */
    public void stop(ManagedProcessKind kind) throws IOException {
        Long pid;
        synchronized (processes) {
            Process p = processes.get(kind);
            if (p == null || !p.isAlive()) {
                throw new IllegalStateException(kind + " nao esta em execucao.");
            }
            pid = p.pid();
        }
        new ProcessBuilder("kill", "-INT", String.valueOf(pid)).start();
    }
}
