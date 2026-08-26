package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadatos del contrato publicado en Swagger UI. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI contentMsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ContentMS")
                .version("1.0.0")
                .description("Carga y consulta de archivos de contenido estatico en el COS de CMS (HU-211)."));
    }
}
