package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.usecase;

import com.bnpparibas.cardif.cloud.contentms.application.annotations.ApplicationComponent;
import com.bnpparibas.cardif.cloud.contentms.domain.annotations.DomainComponent;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Convierte en beans las clases de dominio y aplicacion sin que ellas importen Spring.
 *
 * <p>El escaneo por defecto solo recoge {@code @Component} y derivadas; estos filtros
 * anaden las dos anotaciones propias, que es lo que permite que
 * {@code domain} y {@code application} sean Java puro.
 */
@Configuration
@ComponentScan(
        basePackages = "com.bnpparibas.cardif.cloud.contentms",
        includeFilters = {
                @ComponentScan.Filter(type = FilterType.ANNOTATION, value = ApplicationComponent.class),
                @ComponentScan.Filter(type = FilterType.ANNOTATION, value = DomainComponent.class)
        })
public class UseCaseConfig {
}
