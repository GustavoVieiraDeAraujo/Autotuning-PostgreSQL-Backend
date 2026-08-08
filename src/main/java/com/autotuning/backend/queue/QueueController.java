package com.autotuning.backend.queue;

import com.autotuning.backend.process.ManagedProcessKind;
import com.autotuning.backend.process.ProcessSupervisor;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueueController {

    private final TaskDao taskDao;
    private final ProcessSupervisor processSupervisor;

    public QueueController(TaskDao taskDao, ProcessSupervisor processSupervisor) {
        this.taskDao = taskDao;
        this.processSupervisor = processSupervisor;
    }

    /**
     * Retorna a lista completa de tasks da fila (Postgres).
     *
     * <p>Se o runner nao estiver rodando, qualquer task com status "running"
     * e reescrita para "pending" SO NA RESPOSTA (nao persistido) — reconcilia
     * linhas presas caso um runner tenha morrido sem deixar processo vivo
     * pra eventualmente reivindicar essa tarefa de volta.
     */
    @GetMapping("/api/queue")
    public List<Map<String, Object>> getQueue() {
        List<Map<String, Object>> tasks = taskDao.listTasks();
        if (!processSupervisor.isRunning(ManagedProcessKind.RUNNER)) {
            for (Map<String, Object> task : tasks) {
                if ("running".equals(task.get("status"))) {
                    task.put("status", "pending");
                }
            }
        }
        return tasks;
    }
}
