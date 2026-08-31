package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.cache;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Cache de Redis para el contenido que sirve el GET (paso 1 del HU-211).
 *
 * <p>Solo se instala con {@code cache.enabled} a true (el default). Apagada, ni esta
 * configuracion ni el decorador existen, y el servicio habla directo con el COS.
 *
 * <p><b>Un fallo de Redis degrada a miss, nunca a 500.</b> Lo que hay detras es un almacen
 * durable: que la cache este caida no es motivo para dejar de servir archivos. Eso lo hace
 * el {@link #errorHandler()}, y tiene un precio que conviene tener presente: tambien se
 * traga los fallos de (de)serializacion, asi que una cache que no cachea NADA parece sana
 * desde fuera. La unica prueba de que funciona es ver la clave en Redis, no un 200.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
@ConditionalOnProperty(name = "cache.enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /**
     * Nombre de la cache, que es a la vez el prefijo de las claves en Redis
     * ({@code contentms:content::cmsContent/12345/image1.png}). Tener prefijo propio
     * permite barrer lo de este servicio con {@code contentms:*} sin tocar nada mas de la
     * instancia.
     */
    public static final String CONTENT_CACHE = "contentms:content";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
            CacheProperties properties) {

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                // Un archivo que no existe no se cachea: el GET falla con FileNotFoundError,
                // que es una excepcion y no llega a guardarse. Asi, subirlo despues se ve
                // de inmediato en vez de esperar al TTL.
                .disableCachingNullValues()
                .entryTtl(properties.ttl())
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        GenericJackson2JsonRedisSerializer.builder()
                                .objectMapper(cacheObjectMapper())
                                // Sin el tipo en el JSON, la lectura devolveria un
                                // LinkedHashMap en vez de un StoredFile.
                                .defaultTyping(true)
                                .build()));

        log.info("Cache '{}' activa, TTL de {} minutos", CONTENT_CACHE, properties.ttlMinutes());

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .build();
    }

    /**
     * ObjectMapper propio de la cache, separado del bean web a proposito: lo que se guarda
     * aqui no es una respuesta HTTP y no debe cambiar si algun dia se retoca la
     * serializacion de la API.
     *
     * <p>La configuracion de visibilidad es lo que hace legible un {@code record} como
     * {@code StoredFile}, que no tiene setters ni constructor vacio: {@code ALL/NONE} apaga
     * todo, {@code FIELD/ANY} deja escribir los campos y {@code CREATOR/ANY} hay que
     * restaurarlo explicitamente porque {@code ALL/NONE} tambien lo apago. El
     * {@code ParameterNamesModule} casa los parametros del constructor por su nombre real,
     * que esta en el bytecode porque Spring Boot compila con {@code -parameters}.
     *
     * <p>El {@code byte[]} del contenido viaja como base64 (~33% de sobrecoste): con el
     * tope de 10 MB por archivo que ya impone la politica del bucket, son ~13,4 MB por
     * entrada, muy por debajo de lo que admite Redis.
     */
    private static ObjectMapper cacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES));
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }

    /**
     * Cualquier fallo hablando con Redis se queda en un WARN y la peticion sigue contra el
     * COS. Ver la advertencia del javadoc de la clase.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Fallo leyendo {} de la cache {}: se sirve del COS", key, cache.getName(),
                        exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key,
                    Object value) {
                log.warn("Fallo guardando {} en la cache {}: la respuesta no se cachea", key,
                        cache.getName(), exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Fallo invalidando {} en la cache {}: puede quedar contenido obsoleto"
                        + " hasta que expire el TTL", key, cache.getName(), exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Fallo vaciando la cache {}", cache.getName(), exception);
            }
        };
    }
}
