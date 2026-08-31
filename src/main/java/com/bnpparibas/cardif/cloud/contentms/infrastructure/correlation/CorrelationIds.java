package com.bnpparibas.cardif.cloud.contentms.infrastructure.correlation;

import com.bnpparibas.cardif.cloud.contentms.domain.errors.InvalidUuidHeaderError;
import java.util.regex.Pattern;

/**
 * Comprobacion del formato de los identificadores de trazado ({@code correlation_id},
 * {@code request_id}).
 *
 * <p>Unica fuente de verdad del patron, con dos entradas para los dos usos que tiene:
 * {@link #isUuid(String)} para el {@code CorrelationFilter}, que es trazado best-effort y
 * se limita a descartar lo que no valga, y {@link #optionalUuid(String, String)} para el
 * controller, que es quien hace cumplir el contrato y devuelve el 400.
 *
 * <p>Se valida con expresion regular y no con {@code UUID.fromString}, que acepta en
 * silencio formas cortas no canonicas como {@code 1-1-1-1-1} y las normaliza a un UUID
 * distinto del que escribio el cliente.
 */
public final class CorrelationIds {

    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private CorrelationIds() {
        // Clase de utilidad.
    }

    /** @return true si el valor esta presente y es un UUID canonico. */
    public static boolean isUuid(String value) {
        return value != null && CANONICAL_UUID.matcher(value.trim()).matches();
    }

    /**
     * Valida una cabecera opcional.
     *
     * @param headerName nombre de la cabecera, para el mensaje de error
     * @param value      valor recibido, posiblemente nulo
     * @return el valor recortado, o null si venia ausente o en blanco
     * @throws InvalidUuidHeaderError si viene presente pero no es un UUID canonico
     */
    public static String optionalUuid(String headerName, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!CANONICAL_UUID.matcher(trimmed).matches()) {
            throw new InvalidUuidHeaderError("El header '" + headerName
                    + "' debe ser un UUID valido si se envia: " + trimmed);
        }
        return trimmed;
    }
}
