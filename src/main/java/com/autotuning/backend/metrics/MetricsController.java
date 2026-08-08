package com.autotuning.backend.metrics;

import com.autotuning.backend.hw.HardwareInfoService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {

    private final HardwareInfoService hardwareInfoService;

    public MetricsController(HardwareInfoService hardwareInfoService) {
        this.hardwareInfoService = hardwareInfoService;
    }

    /** Retorna snapshot atual de metricas de hardware do servidor. */
    @GetMapping("/api/metrics")
    public Map<String, Object> getMetrics() {
        return hardwareInfoService.snapshot();
    }
}
