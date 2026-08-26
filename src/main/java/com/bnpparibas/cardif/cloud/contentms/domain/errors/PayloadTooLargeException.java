package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * El cuerpo de la peticion supera el maximo admitido: 413.
 */
public class PayloadTooLargeException extends DomainException {

    public PayloadTooLargeException(String message, String code) {
        super(message, code, 413);
    }

    public PayloadTooLargeException(String message, String code, Throwable cause) {
        super(message, code, 413, cause);
    }
}
