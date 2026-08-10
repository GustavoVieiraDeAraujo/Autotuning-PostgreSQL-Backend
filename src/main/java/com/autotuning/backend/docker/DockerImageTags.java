package com.autotuning.backend.docker;

import java.util.List;

/**
 * As 6 tags de imagem Docker necessarias para rodar os benchmarks.
 *
 * <p>Espelha manualmente {@code TIER_IMAGE_TAGS} de
 * {@code Autotuning-PostgreSQL-Pipeline/benchmarks/image_builder.py}:
 * essa e a UNICA fonte de verdade real (confirmado: {@code specs/docker.json}
 * na Pipeline so tem limites de recurso por tier, nao tags de imagem). Se
 * a lista mudar la, precisa ser atualizada aqui manualmente tambem, mesmo
 * "wart" que o backend Python original ja tinha (a lista de lá tambem era
 * hand-duplicada, so que dentro do proprio monorepo).
 */
public final class DockerImageTags {

    public static final List<String> REQUIRED = List.of(
            "tpch-postgres:sf1", "tpch-postgres:sf2", "tpch-postgres:sf4",
            "tpcds-postgres:sf1", "tpcds-postgres:sf2", "tpcds-postgres:sf4"
    );

    private DockerImageTags() {}
}
