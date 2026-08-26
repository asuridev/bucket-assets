package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.logging;

import com.bnpparibas.cardif.cloud.contentms.application.annotations.LogExceptions;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementa {@link LogExceptions}: registra la excepcion y la vuelve a lanzar.
 *
 * <p>Relanzar siempre es lo que mantiene honesto al contrato: este aspecto observa,
 * no decide. Quien traduce la excepcion a una respuesta es el
 * {@code ApiExceptionHandler}.
 */
@Aspect
@Component
public class LogExceptionsAspect {

    private static final Logger log = LoggerFactory.getLogger(LogExceptionsAspect.class);

    @Around("@annotation(logExceptions)")
    public Object logExceptions(ProceedingJoinPoint joinPoint, LogExceptions logExceptions)
            throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (Throwable exception) {
            String where = joinPoint.getSignature().toShortString();
            switch (logExceptions.level()) {
                case DEBUG -> log.debug("Excepcion en {}: {}", where, exception.getMessage(), exception);
                case INFO -> log.info("Excepcion en {}: {}", where, exception.getMessage(), exception);
                case WARN -> log.warn("Excepcion en {}: {}", where, exception.getMessage(), exception);
                case ERROR -> log.error("Excepcion en {}: {}", where, exception.getMessage(), exception);
            }
            throw exception;
        }
    }
}
