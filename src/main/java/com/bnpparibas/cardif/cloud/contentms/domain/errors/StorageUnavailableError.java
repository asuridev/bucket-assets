package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * El COS no respondio o respondio con un fallo que no es de negocio.
 *
 * <p>Es 503 y no 500 porque la causa es una dependencia externa caida, no un bug
 * nuestro: quien llama puede reintentar.
 */
public class StorageUnavailableError extends DomainException {

    public static final String CODE = "STORAGE_UNAVAILABLE";

    public StorageUnavailableError(String message, Throwable cause) {
        super(message, CODE, 503, cause);
    }
}
