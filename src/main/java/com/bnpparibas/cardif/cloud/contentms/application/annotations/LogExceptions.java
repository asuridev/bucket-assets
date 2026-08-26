package com.bnpparibas.cardif.cloud.contentms.application.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registra la excepcion que salga del metodo y la vuelve a lanzar.
 *
 * <p>La implementa un aspecto en infraestructura, de modo que el caso de uso declara
 * la intencion sin depender de AOP ni de SLF4J.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogExceptions {

    LogLevel level() default LogLevel.WARN;
}
