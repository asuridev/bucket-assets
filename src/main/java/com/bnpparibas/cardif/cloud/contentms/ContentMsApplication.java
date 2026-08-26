package com.bnpparibas.cardif.cloud.contentms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ContentMS (HU-211): microservicio de contenido estatico sobre IBM Cloud Object
 * Storage, pensado para desplegarse en Code Engine.
 *
 * <p>El escaneo de componentes de dominio y aplicacion lo anade
 * {@code infrastructure.configurations.usecase.UseCaseConfig}, que suma las
 * anotaciones propias a las de Spring.
 */
@SpringBootApplication
public class ContentMsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentMsApplication.class, args);
    }
}
