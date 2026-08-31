package com.bnpparibas.cardif.cloud.contentms.infrastructure.rest;

import com.bnpparibas.cardif.cloud.contentms.domain.errors.DomainException;
import jakarta.validation.ConstraintViolationException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Traduce toda excepcion al cuerpo de error del HU-211.
 *
 * <p>Reparto de estados: los errores de FORMA de la peticion son 400; las reglas de
 * negocio tipadas llevan el estado que declara su {@code DomainException}. Los fallos
 * no previstos caen en el catch-all como 500 y se registran con stack completo — al
 * cliente nunca se le manda el stack salvo que el perfil lo habilite.
 *
 * <p>Es tambien el UNICO punto que registra excepciones: por aqui pasa todo — los errores
 * de negocio, los que Spring lanza antes de entrar al controlador y los inesperados — y es
 * el unico sitio que conoce ya el estado HTTP final, que es lo que fija la gravedad.
 *
 * <p>De ahi el reparto de niveles: un 4xx es una respuesta prevista del contrato, no un
 * incidente, y va en INFO <b>sin stack</b>. Un stack de sesenta lineas por cada archivo que
 * no existe entierra los fallos de verdad y dispara las alertas que cuentan WARN. El stack
 * se reserva para lo que si hay que investigar: 5xx y catch-all.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String CODE_VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String CODE_MALFORMED_REQUEST = "MALFORMED_REQUEST";
    private static final String CODE_MISSING_HEADER = "MISSING_REQUIRED_HEADER";
    private static final String CODE_MISSING_PARAMETER = "MISSING_REQUIRED_PARAMETER";
    private static final String CODE_MISSING_PART = "MISSING_REQUIRED_PART";
    private static final String CODE_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    private static final String CODE_FILE_TOO_LARGE = "FILE_TOO_LARGE";
    private static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    /**
     * Si se expone el stack en la respuesta. Falso en produccion: un stack trace en el
     * cuerpo filtra rutas, versiones y estructura interna a quien llame.
     */
    private final boolean exposeErrorStack;

    public ApiExceptionHandler(@Value("${api.expose-error-stack:false}") boolean exposeErrorStack) {
        this.exposeErrorStack = exposeErrorStack;
    }

    // -- Validacion y forma de la peticion: 400 --------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        String details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return buildAndLog(HttpStatus.BAD_REQUEST, CODE_VALIDATION_ERROR,
                "La peticion no supera las validaciones: " + details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> onConstraintViolation(ConstraintViolationException exception) {
        String details = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return buildAndLog(HttpStatus.BAD_REQUEST, CODE_VALIDATION_ERROR,
                "La peticion viola restricciones declaradas: " + details);
    }

    /**
     * Faltan los headers obligatorios del POST ({@code correlation_id},
     * {@code request_id}, {@code _p}). Spring los rechaza antes de Bean Validation, asi
     * que sin este handler el caso caeria en el catch-all como 500.
     *
     * <p>El GET de descarga no declara ninguno, asi que nunca llega por aqui.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> onMissingHeader(MissingRequestHeaderException exception) {
        return buildAndLog(HttpStatus.BAD_REQUEST, CODE_MISSING_HEADER,
                "Falta el header obligatorio '" + exception.getHeaderName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> onMissingParameter(MissingServletRequestParameterException exception) {
        return buildAndLog(HttpStatus.BAD_REQUEST, CODE_MISSING_PARAMETER,
                "Falta el parametro '" + exception.getParameterName() + "' en la peticion");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> onMissingPart(MissingServletRequestPartException exception) {
        return buildAndLog(HttpStatus.BAD_REQUEST, CODE_MISSING_PART,
                "Falta la parte '" + exception.getRequestPartName() + "' en la peticion multipart");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> onMalformedRequest(Exception exception) {
        return buildAndLog(HttpStatus.BAD_REQUEST, CODE_MALFORMED_REQUEST, "Peticion malformada");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> onMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return buildAndLog(HttpStatus.METHOD_NOT_ALLOWED, CODE_METHOD_NOT_ALLOWED,
                "Metodo HTTP no soportado");
    }

    /**
     * El limite de {@code spring.servlet.multipart.max-file-size} salta antes de que el
     * caso de uso pueda comparar contra la politica del bucket, asi que este 413 y el
     * de {@code FileTooLargeError} son dos caminos al mismo contrato.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> onMaxUploadSizeExceeded(MaxUploadSizeExceededException exception) {
        return buildAndLog(HttpStatus.PAYLOAD_TOO_LARGE, CODE_FILE_TOO_LARGE,
                "El archivo supera el tamano maximo permitido");
    }

    // -- Errores de negocio tipados --------------------------------------------

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> onDomainException(DomainException exception) {
        HttpStatus status = exception.getHttpStatus() == null
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.valueOf(exception.getHttpStatus());

        if (status.is5xxServerError()) {
            log.error("Fallo de dependencia: {}", exception.getMessage(), exception);
        } else {
            // Sin la excepcion como ultimo argumento: es justamente lo que evita el stack.
            log.info("Error de negocio [{}]: {}", exception.getCode(), exception.getMessage());
        }

        return build(status, exception.getCode(), exception.getMessage(), exception);
    }

    // -- Catch-all --------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onServerError(Exception exception) {
        log.error("Excepcion no controlada", exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, CODE_INTERNAL_ERROR,
                "Ocurrio un error inesperado", exception);
    }

    /**
     * Registra y compone los errores de FORMA de la peticion.
     *
     * <p>Van en INFO y sin stack por lo mismo que los 4xx de negocio: son un error de quien
     * llama, no un fallo del servicio. Hasta ahora no dejaban ninguna linea, asi que un 400
     * por un header que falta era invisible en el log.
     */
    private ResponseEntity<ErrorResponse> buildAndLog(HttpStatus status, String code, String message) {
        log.info("Peticion rechazada [{}]: {}", code, message);
        return build(status, code, message, null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
            Throwable cause) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), status.getReasonPhrase(), code, message,
                        stackOf(status, cause)));
    }

    /**
     * El stack solo tiene sentido en un fallo inesperado. Un 404 o un 400 son
     * condiciones normales del contrato: adjuntarles el stack ahoga la respuesta en
     * ruido y no dice nada que el {@code code} no diga ya.
     *
     * @return el stack como texto, o null — el HU pide NULL cuando no hay ninguno
     */
    private String stackOf(HttpStatus status, Throwable cause) {
        if (!exposeErrorStack || cause == null || !status.is5xxServerError()) {
            return null;
        }
        StringWriter writer = new StringWriter();
        cause.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
