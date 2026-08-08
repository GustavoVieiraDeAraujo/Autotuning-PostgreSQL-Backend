package com.autotuning.backend.stream;

import com.autotuning.backend.config.PipelinePathsConfig;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class LogStreamController {

    private final LogStreamService logStreamService;
    private final PipelinePathsConfig paths;

    public LogStreamController(LogStreamService logStreamService, PipelinePathsConfig paths) {
        this.logStreamService = logStreamService;
        this.paths = paths;
    }

    /** Streaming do generate.log (geracao da fila + LHS). */
    @GetMapping("/stream/generate")
    public SseEmitter streamGenerate(HttpServletResponse response) {
        setSseHeaders(response);
        return logStreamService.stream(paths.logFile("generate"));
    }

    /** Streaming do prepare.log (construcao das imagens Docker). */
    @GetMapping("/stream/prepare")
    public SseEmitter streamPrepare(HttpServletResponse response) {
        setSseHeaders(response);
        return logStreamService.stream(paths.logFile("prepare"));
    }

    /** Streaming do runner.log (execucao dos benchmarks). */
    @GetMapping("/stream/runner")
    public SseEmitter streamRunner(HttpServletResponse response) {
        setSseHeaders(response);
        return logStreamService.stream(paths.logFile("runner"));
    }

    private static void setSseHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
    }
}
