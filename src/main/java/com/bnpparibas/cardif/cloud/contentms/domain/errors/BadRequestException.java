package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * Peticion malformada o incompleta: 400.
 */
public class BadRequestException extends DomainException {

    public BadRequestException(String message, String code) {
        super(message, code, 400);
    }

    public BadRequestException(String message, String code, Throwable cause) {
        super(message, code, 400, cause);
    }
}
