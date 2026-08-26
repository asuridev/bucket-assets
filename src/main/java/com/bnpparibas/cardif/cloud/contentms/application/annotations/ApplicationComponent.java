package com.bnpparibas.cardif.cloud.contentms.application.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un caso de uso como candidato a bean.
 *
 * <p>Existe para que la capa de aplicacion no importe {@code @Component} de Spring:
 * la recoge el {@code @ComponentScan} con includeFilters de
 * {@code infrastructure.configurations.usecase.UseCaseConfig}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApplicationComponent {
}
