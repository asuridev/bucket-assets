package com.bnpparibas.cardif.cloud.contentms.infrastructure.rest;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Cuerpo de error del HU-211: {@code errorHeader} + {@code errorDetail}.
 *
 * <p>La forma es contrato con el consumidor, asi que los campos sin valor viajan como
 * {@code null} en vez de omitirse — el HU dice explicitamente que
 * {@code errorStack} "mostrara un valor NULL" cuando no haya stack disponible.
 */
public record ErrorResponse(ErrorHeader errorHeader, ErrorDetail errorDetail) {

    /**
     * @param returnCode codigo HTTP como entero (400, 401, 500...)
     * @param message    frase estandar del codigo ("Bad Request", "Unauthorized"...)
     */
    public record ErrorHeader(Integer returnCode, String message) {
    }

    /**
     * @param code       codigo de error de la aplicacion
     * @param message    mensaje de error de la aplicacion
     * @param errorStack stack de error, o null si no hay ninguno disponible
     * @param errorDate  fecha del error en ISO-8601
     */
    public record ErrorDetail(String code, String message, String errorStack, String errorDate) {
    }

    public static ErrorResponse of(int status, String reasonPhrase, String code, String message,
            String errorStack) {
        return new ErrorResponse(
                new ErrorHeader(status, reasonPhrase),
                new ErrorDetail(code, message, errorStack,
                        OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
    }
}
