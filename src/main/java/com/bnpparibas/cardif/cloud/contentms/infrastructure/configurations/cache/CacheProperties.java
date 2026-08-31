package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de la cache del GET.
 *
 * <p>El HU-211 no fija cuanto vive una entrada, y no es lo mismo en local (donde interesa
 * verla expirar) que en produccion (donde el contenido es estatico migrado de Liferay y
 * puede vivir horas). Por eso el TTL sale de aqui y no del codigo, a diferencia del
 * proyecto de referencia, donde es un artefacto generado del diseno.
 *
 * @param enabled    si se instala el decorador de cache. Con {@code false} el servicio se
 *                   comporta exactamente como antes de esta funcionalidad: util en un
 *                   entorno donde todavia no hay instancia de Redis provisionada
 * @param ttlMinutes minutos que vive una entrada. Un valor no positivo se trata como no
 *                   declarado: es mas seguro caer al default que cachear para siempre
 */
@ConfigurationProperties("cache")
public record CacheProperties(
        Boolean enabled,
        Integer ttlMinutes
) {

    private static final int DEFAULT_TTL_MINUTES = 60;

    public CacheProperties {
        enabled = enabled == null || enabled;
        ttlMinutes = ttlMinutes == null || ttlMinutes <= 0 ? DEFAULT_TTL_MINUTES : ttlMinutes;
    }

    /** El TTL ya como {@link Duration}, que es lo que espera el {@code RedisCacheManager}. */
    public Duration ttl() {
        return Duration.ofMinutes(ttlMinutes);
    }
}
