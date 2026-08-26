package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * El recurso pedido no existe: 404.
 */
public class NotFoundException extends DomainException {

    public NotFoundException(String message, String code) {
        super(message, code, 404);
    }

    public NotFoundException(String message, String code, Throwable cause) {
        super(message, code, 404, cause);
    }
}
