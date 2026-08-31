package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * Llego una cabecera de trazado con un valor que no es un UUID canonico.
 *
 * <p>Solo se lanza para cabeceras OPCIONALES: la ausencia no es un error, pero un valor
 * presente y malformado si lo es — un identificador basura en el log no traza nada y
 * ensucia la correlacion de extremo a extremo.
 */
public class InvalidUuidHeaderError extends BadRequestException {

    public static final String CODE = "INVALID_UUID_HEADER";

    public InvalidUuidHeaderError(String message) {
        super(message, CODE);
    }
}
