package com.autotuning.backend;

import com.autotuning.backend.config.PipelinePathsConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

    /** Espelha o `print(f"[api] PIPELINE_ROOT = ...")` do backend Python original. */
    @Component
    static class StartupLogger {
        private final PipelinePathsConfig paths;

        StartupLogger(PipelinePathsConfig paths) {
            this.paths = paths;
        }

        @EventListener(ApplicationReadyEvent.class)
        void logPipelineRoot() {
            System.out.println("[backend] PIPELINE_ROOT = " + paths.getRoot());
        }
    }
}
