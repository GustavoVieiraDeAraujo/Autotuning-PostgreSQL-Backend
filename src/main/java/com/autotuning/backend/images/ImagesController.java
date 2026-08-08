package com.autotuning.backend.images;

import com.autotuning.backend.docker.DockerService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImagesController {

    private final DockerService dockerService;

    public ImagesController(DockerService dockerService) {
        this.dockerService = dockerService;
    }

    /** Verifica se as imagens Docker necessarias estao disponiveis. */
    @GetMapping("/api/images/status")
    public Map<String, Object> imagesStatus() {
        return dockerService.imagesStatus();
    }
}
