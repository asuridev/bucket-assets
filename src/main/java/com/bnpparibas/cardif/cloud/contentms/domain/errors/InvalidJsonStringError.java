package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * El part jsonString no es un JSON valido o le faltan campos obligatorios.
 */
public class InvalidJsonStringError extends BadRequestException {

    public static final String CODE = "INVALID_JSON_STRING";

    public InvalidJsonStringError(String message) {
        super(message, CODE);
    }
}
