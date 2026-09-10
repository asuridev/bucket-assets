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

    /** La del service credential de Redis, separada a proposito. Ver el metodo que la crea. */
    static final String REDIS_PROPERTY_SOURCE_NAME = "ibm-secrets-manager-redis";

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
            warnIfCacheWithoutSecret(binder);
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

        addRedisCredentials(environment, binder, properties);
    }

    /**
     * El service credential de Redis, en una fuente de propiedades <b>aparte</b>.
     *
     * <p>Aparte y no fusionada con la anterior por dos motivos: si algo falla, el mensaje
     * dice cual de los dos secretos fue, y en {@code /actuator/env} se ve de que secreto sale
     * cada propiedad.
     *
     * <p><b>Este es el unico sitio que define {@code spring.data.redis.*}.</b> Los
     * {@code cache.yaml} solo llevan ya {@code cache.enabled} y {@code cache.ttl-minutes}: el
     * host, el puerto, el usuario, la password y el TLS salen del secreto y de ningun otro
     * sitio. Eso permite registrarlo con {@code addLast()}, igual que el kv, sin que nadie
     * pise a nadie — y una variable de entorno real ({@code SPRING_DATA_REDIS_HOST}) sigue
     * ganando, que es la via de escape de siempre.
     *
     * <p>Hubo una version anterior en la que {@code cache.yaml} declaraba tambien esas claves
     * y habia que insertar esta fuente por encima de los YAML para que no la pisaran. Se
     * quito: mientras existio, un {@code REDIS_PORT} en el despliegue no hacia lo que
     * cualquiera esperaria.
     */
    private void addRedisCredentials(ConfigurableEnvironment environment, Binder binder,
            SecretsProperties properties) {

        if (!cacheEnabled(binder)) {
            log.info("Cache desactivada (cache.enabled=false): no se lee el service credential"
                    + " de Redis, porque no hay conexion que configurar");
            return;
        }

        Map<String, Object> redis = redisSource(properties).fetch();

        // addLast, igual que el secreto kv: nada mas declara spring.data.redis.*, asi que no
        // hay a quien pisar, y una variable de entorno real sigue ganando.
        environment.getPropertySources().addLast(
                new MapPropertySource(REDIS_PROPERTY_SOURCE_NAME, new LinkedHashMap<>(redis)));

        // Aqui las claves son nombres de propiedades de Spring, no del secreto, asi que no
        // revelan nada. El certificado ni siquiera se nombra por su tamano: basta con saber
        // que el bundle quedo registrado.
        log.info(String.format(
                "Secrets Manager: conexion a Redis tomada del service credential '%s' (grupo"
                        + " '%s'); %d propiedades %s",
                properties.redis().name(), properties.redis().group(), redis.size(),
                new TreeSet<>(redis.keySet())));
    }

    /**
     * {@code cache.enabled} es el unico interruptor de esta funcionalidad.
     *
     * <p>Se enlaza a mano en vez de usar {@code CacheProperties}, que es un bean y todavia no
     * existe cuando corre un {@code EnvironmentPostProcessor}. El default es {@code true}, el
     * mismo que aplica {@code CacheConfig} con su {@code matchIfMissing}: los dos tienen que
     * decir lo mismo o se instalaria el decorador sin conexion configurada.
     */
    private static boolean cacheEnabled(Binder binder) {
        return binder.bind("cache.enabled", Boolean.class).orElse(true);
    }

    /**
     * Aviso para la combinacion que deja la conexion a Redis sin configurar: Secrets Manager
     * apagado pero cache encendida.
     *
     * <p>No se aborta el arranque porque {@code secrets.enabled=false} es una via de escape
     * legitima —trabajar en local sin el stub— y ahi los defaults de Spring Boot
     * ({@code localhost:6379}, en claro) son justo lo que se quiere. Pero en un entorno real
     * esa combinacion da una cache que no cachea nada mientras todo responde 200, asi que
     * tiene que dejar rastro.
     */
    private void warnIfCacheWithoutSecret(Binder binder) {
        if (cacheEnabled(binder)) {
            log.warn("cache.enabled=true con secrets.enabled=false: la conexion a Redis NO sale"
                    + " del service credential y cae a los defaults de Spring Boot"
                    + " (localhost:6379, sin TLS). Si esto no es una maquina de desarrollo, la"
                    + " cache no va a funcionar y el servicio no se va a quejar");
        }
    }

    /** Punto de sustitucion para las pruebas; en produccion solo hay un origen. */
    SecretsSource source(SecretsProperties properties) {
        return new IbmSecretsManagerSource(properties);
    }

    /** Idem, para el service credential de Redis. */
    SecretsSource redisSource(SecretsProperties properties) {
        return new IbmRedisCredentialsSource(properties);
    }
}
