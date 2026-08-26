package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * Regla de negocio incumplida sobre una peticion bien formada: 422.
 */
public class BusinessException extends DomainException {

    public BusinessException(String message, String code) {
        super(message, code, 422);
    }

    public BusinessException(String message, String code, Throwable cause) {
        super(message, code, 422, cause);
    }
}
