package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * Raiz de los errores de negocio del servicio.
 *
 * <p>Lleva la metadata que el {@code ApiExceptionHandler} necesita para componer el
 * cuerpo de error del HU-211 sin conocer cada error concreto: el {@code code} de
 * aplicacion (el {@code errorDetail.code} del contrato) y el estado HTTP.
 *
 * <p>Los fallos de infraestructura (timeouts del COS, cortes de red) NO extienden de
 * aqui a proposito: son reintentables y deben caer en el catch-all como 500, no
 * confundirse con una condicion de negocio.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final Integer httpStatus;

    protected DomainException(String message, String code, Integer httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    protected DomainException(String message, String code, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}
