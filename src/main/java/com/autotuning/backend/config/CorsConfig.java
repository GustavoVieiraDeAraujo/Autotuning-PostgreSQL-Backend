package com.autotuning.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS liberado para qualquer origem — adequado apenas para desenvolvimento
 * local. O frontend roda como site separado (porta/origem diferente do
 * backend), diferente do antigo web/app.py monolitico onde frontend+backend
 * eram same-origin e CORS nao era necessario.
 *
 * <p>ATENCAO: em qualquer deployment real, restringir {@code allowedOrigins}
 * a origem exata do frontend.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}
