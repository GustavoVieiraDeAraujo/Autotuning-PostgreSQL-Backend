package com.autotuning.backend.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Wrapper fino sobre docker-java: equivalente Java de {@code docker.from_env()}
 * do backend Python original (le DOCKER_HOST do ambiente, ou usa o socket
 * unix padrao se ausente).
 */
@Service
public class DockerService {

    private static final List<String> BENCHMARK_CONTAINER_PREFIXES = List.of(
            "tpch_bench_", "tpcds_bench_", "tpch-build-tmp-", "tpcds-build-tmp-"
    );

    private DockerClient newClient(Duration timeout) {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(timeout)
                .responseTimeout(timeout)
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    /** Verifica se as imagens Docker necessarias estao disponiveis. */
    public Map<String, Object> imagesStatus() {
        try (DockerClient client = newClient(Duration.ofSeconds(10))) {
            List<Image> images = client.listImagesCmd().exec();
            Set<String> available = new HashSet<>();
            for (Image img : images) {
                if (img.getRepoTags() != null) {
                    available.addAll(List.of(img.getRepoTags()));
                }
            }
            Map<String, Boolean> status = new LinkedHashMap<>();
            boolean allReady = true;
            for (String tag : DockerImageTags.REQUIRED) {
                boolean ready = available.contains(tag);
                status.put(tag, ready);
                allReady &= ready;
            }
            return Map.of("ready", allReady, "images", status);
        } catch (Exception e) {
            return Map.of("ready", false, "images", Map.of(), "error", String.valueOf(e.getMessage()));
        }
    }

    /** Para e remove todos os containers de benchmark e build criados pelo sistema. */
    public List<String> removeBenchmarkContainers() {
        List<String> removed = new ArrayList<>();
        try (DockerClient client = newClient(Duration.ofSeconds(30))) {
            List<Container> containers = client.listContainersCmd().withShowAll(true).exec();
            for (Container c : containers) {
                String name = c.getNames() != null && c.getNames().length > 0
                        ? c.getNames()[0].replaceFirst("^/", "") : "";
                boolean matches = BENCHMARK_CONTAINER_PREFIXES.stream().anyMatch(name::startsWith);
                if (matches) {
                    try {
                        client.removeContainerCmd(c.getId()).withForce(true).exec();
                        removed.add(name);
                    } catch (Exception ignored) {
                        // best-effort, igual ao backend Python original
                    }
                }
            }
        } catch (Exception ignored) {
            // best-effort: Docker indisponivel nao deve quebrar /api/reset
        }
        return removed;
    }
}
