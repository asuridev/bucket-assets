package com.bnpparibas.cardif.cloud.contentms.domain.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca una clase de dominio como candidata a bean.
 *
 * <p>Existe para que el dominio no tenga que importar {@code @Component} de Spring:
 * la recoge el {@code @ComponentScan} con includeFilters de
 * {@code infrastructure.configurations.usecase.UseCaseConfig}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainComponent {
}
