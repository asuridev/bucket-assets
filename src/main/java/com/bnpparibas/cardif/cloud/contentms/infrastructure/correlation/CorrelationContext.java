package com.bnpparibas.cardif.cloud.contentms.infrastructure.correlation;

import org.slf4j.MDC;

/**
 * Contexto de correlacion del hilo actual: los tres headers que el HU-211 declara
 * obligatorios ({@code correlation_id}, {@code request_id}, {@code _p}), que en este
 * servicio solo exige el POST — el GET de descarga no los lleva y por tanto se atiende
 * sin contexto abierto.
 *
 * <p>El valor se escribe a la vez en un ThreadLocal y en el MDC de SLF4J: el primero
 * lo lee el codigo (el cuerpo de error, al estampar la correlacion) y el segundo hace
 * que aparezca en cada linea de log sin pasarlo por parametro.
 *
 * <p>Los hilos se reutilizan: quien abre el contexto SIEMPRE debe cerrarlo, o la
 * proxima peticion atendida por ese hilo heredara una correlacion ajena. Lo abre y lo
 * cierra {@code CorrelationFilter} en un finally.
 */
public final class CorrelationContext {

    /** Clave con la que el correlationId aparece en el MDC y en el patron de log. */
    public static final String MDC_CORRELATION_ID = "correlationId";

    /** Clave con la que el requestId aparece en el MDC. */
    public static final String MDC_REQUEST_ID = "requestId";

    private static final ThreadLocal<Values> CURRENT = new ThreadLocal<>();

    /**
     * Los tres identificadores de la peticion en curso.
     *
     * @param correlationId correlacion de extremo a extremo
     * @param requestId     identificador unico de esta peticion
     * @param partnerId     id de socio, del header {@code _p}
     */
    public record Values(String correlationId, String requestId, String partnerId) {
    }

    private CorrelationContext() {
        // Clase de utilidad.
    }

    public static void set(String correlationId, String requestId, String partnerId) {
        CURRENT.set(new Values(correlationId, requestId, partnerId));
        putIfPresent(MDC_CORRELATION_ID, correlationId);
        putIfPresent(MDC_REQUEST_ID, requestId);
    }

    /** @return los identificadores del hilo actual, o null si no hay contexto abierto. */
    public static Values get() {
        return CURRENT.get();
    }

    /** @return la correlacion del hilo actual, o null. */
    public static String correlationId() {
        Values values = CURRENT.get();
        return values == null ? null : values.correlationId();
    }

    /** Cierra el contexto. Va siempre en un finally. */
    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_CORRELATION_ID);
        MDC.remove(MDC_REQUEST_ID);
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }
}
