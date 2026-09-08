package com.bnpparibas.cardif.cloud.contentms.infrastructure.secrets;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Vuelca las claves del secreto de IBM Cloud Secrets Manager en el {@code Environment},
 * al arrancar, como una fuente de propiedades mas.
 *
 * <p>Es el punto en el que este servicio conecta con Secrets Manager, y esta aqui y no en
 * un bean a proposito. Las claves del secreto se llaman <b>igual que las variables de
 * entorno que ya usan los YAML</b> ({@code COS_API_KEY}, {@code COS_SERVICE_INSTANCE_ID},
 * {@code REDIS_PASSWORD}), asi que registrarlas como propiedades hace que los
 * {@code ${COS_API_KEY}} de {@code parameters/<perfil>/storage.yaml} resuelvan solos:
 * ni {@code StorageProperties}, ni {@code CosConfig}, ni un solo YAML saben que Secrets
 * Manager existe. El proyecto de referencia lo resuelve al reves — un bean tipado que
 * cada consumidor inyecta — que es mas explicito pero obliga a tocar codigo por cada
 * secreto nuevo.
 *
 * <p>Se registra con {@code addLast()}: las variables de entorno reales <b>ganan</b> al
 * secreto. Eso deja una via de escape para sobreescribir un valor puntual en un
 * despliegue sin tener que editar el secreto.
 *
 * <p><b>Si el secreto no se puede leer, la aplicacion no arranca.</b> Es lo contrario de
 * lo que hace la cache, que degrada a miss: una cache caida es tolerable porque detras
 * hay un almacen durable, mientras que unas credenciales ausentes solo pueden acabar en
 * un 500 en la primera peticion. Es el mismo criterio que ya aplica
 * {@code CosConfig.requireConfigured}.
 *
 * <p>Se lee <b>una sola vez, al arrancar</b>. No hay refresco: si las credenciales rotan,
 * se reinicia el pod. Ver secret-manager.md.
 */
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Nombre de la fuente de propiedades; sale tal cual en {@code /actuator/env}. */
    static final String PROPERTY_SOURCE_NAME = "ibm-secrets-manager";

    private final Log log;

    /**
     * Un {@code EnvironmentPostProcessor} corre antes de que exista el sistema de logging,
     * asi que un {@code LoggerFactory.getLogger} de SLF4J se tragaria estas trazas. Spring
     * Boot inyecta este {@link DeferredLogFactory} por constructor justo para eso: guarda
     * los mensajes y los emite cuando el logging ya esta en pie.
     */
    public SecretsEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(SecretsEnvironmentPostProcessor.class);
    }

    /**
     * Al final del todo: hace falta que {@code ConfigDataEnvironmentPostProcessor} haya
     * cargado ya los {@code application-<perfil>.yaml}, porque de ahi sale {@code secrets.*}.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
            SpringApplication application) {

        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }

        Binder binder = Binder.get(environment);

        // El flag se enlaza aparte, y primero, a proposito: en develop/production el resto
        // de secrets.* son ${VARIABLE} sin default, y enlazar el record entero reventaria
        // por un placeholder sin resolver incluso con la funcionalidad apagada. Asi
        // secrets.enabled=false sigue siendo una via de escape de verdad.
        if (!binder.bind("secrets.enabled", Boolean.class).orElse(false)) {
            log.info("Secrets Manager desactivado (secrets.enabled=false):"
                    + " los secretos tienen que llegar por variable de entorno");
            return;
        }

        SecretsProperties properties = binder.bind("secrets", Bindable.of(SecretsProperties.class))
                .orElseThrow(() -> new IllegalStateException(
                        "secrets.enabled=true pero no hay configuracion secrets.*."
                                + " Revisa parameters/<perfil>/secrets.yaml"));

        Map<String, Object> secret = source(properties).fetch();

        // addLast, no addFirst: una variable de entorno real pisa al secreto.
        environment.getPropertySources()
                .addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, new LinkedHashMap<>(secret)));

        // Solo las claves. Los valores son justo lo que no puede acabar en un log.
        log.info(String.format("Secrets Manager: %d claves cargadas del secreto '%s' (grupo '%s') %s",
                secret.size(), properties.name(), properties.group(),
                new TreeSet<>(secret.keySet())));
    }

    /** Punto de sustitucion para las pruebas; en produccion solo hay un origen. */
    SecretsSource source(SecretsProperties properties) {
        return new IbmSecretsManagerSource(properties);
    }
}
