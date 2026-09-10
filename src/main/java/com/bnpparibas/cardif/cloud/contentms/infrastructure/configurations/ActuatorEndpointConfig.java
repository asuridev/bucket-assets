package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations;

import java.util.List;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

/**
 * Fuerza a los endpoints del actuator a responder {@code application/json}.
 *
 * <p>Por defecto contestan con el media type propietario de Spring Boot,
 * {@code application/vnd.spring-boot.actuator.v3+json}, y el escaneo de seguridad (ZAP) lo marca
 * como hallazgo. Comprobado en esta rama antes del cambio: {@code GET /actuator/health} devolvia
 * exactamente esa cabecera.
 *
 * <p><b>Esto no cambia el cuerpo de la respuesta</b>, que ya era JSON, solo como se anuncia. Las
 * sondas de Code Engine ({@code /actuator/health/liveness} y {@code /readiness}) siguen
 * respondiendo igual.
 *
 * <p>Procedencia: plantilla de despliegue de DevOps, starter {@code ap13002-content-ms-app-repo}
 * rama {@code release-v6.5.2}. Ver README seccion 8 para lo que se adopto de ella y lo que no.
 */
@Configuration
public class ActuatorEndpointConfig {

    @Bean
    public EndpointMediaTypes endpointMediaTypes() {
        List<String> json = List.of(MediaType.APPLICATION_JSON_VALUE);
        return new EndpointMediaTypes(json, json);
    }
}
