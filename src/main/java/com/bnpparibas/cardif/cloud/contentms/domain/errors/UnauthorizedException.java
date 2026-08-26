package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * Credenciales ausentes o invalidas: 401.
 */
public class UnauthorizedException extends DomainException {

    public UnauthorizedException(String message, String code) {
        super(message, code, 401);
    }

    public UnauthorizedException(String message, String code, Throwable cause) {
        super(message, code, 401, cause);
    }
}
