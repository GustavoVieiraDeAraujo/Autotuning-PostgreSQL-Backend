package com.autotuning.backend.serverinfo;

import com.autotuning.backend.hw.HardwareInfoService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServerInfoController {

    private final HardwareInfoService hardwareInfoService;

    public ServerInfoController(HardwareInfoService hardwareInfoService) {
        this.hardwareInfoService = hardwareInfoService;
    }

    /** Retorna informacoes estaticas do servidor (CPU, RAM, sensores disponiveis). */
    @GetMapping("/api/server-info")
    public Map<String, Object> getServerInfo() {
        return hardwareInfoService.serverInfo();
    }
}
