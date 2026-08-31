package com.bnpparibas.cardif.cloud.contentms.infrastructure.storage;

import com.bnpparibas.cardif.cloud.contentms.domain.storage.FileStorage;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoredFile;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoredObject;
import com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.cache.CacheConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Decorador de cache sobre {@link FileStorage}: implementa el paso 1 del GET del HU-211
 * (mirar Redis, ir al COS si no esta, repoblar).
 *
 * <p><b>Por que aqui y no en el caso de uso.</b> Este puerto lo atraviesan los DOS
 * endpoints, asi que poner la cache en el envuelve tambien al POST y la invalidacion sale
 * gratis: subir de nuevo el mismo {@code fileName} borra la entrada, y el GET siguiente no
 * puede servir el binario viejo. Una cache colgada del caso de uso del GET no veria esa
 * escritura. De paso, {@code domain} y {@code application} no se enteran de que existe
 * Redis: {@link com.bnpparibas.cardif.cloud.contentms.application.usecases.GetContentLoadedQueryHandler}
 * sigue pidiendole el archivo al puerto, sin una linea de cambio.
 *
 * <p>El {@code @Primary} es lo que hace que los handlers reciban esta version. Con
 * {@code cache.enabled: false} este bean no existe y {@link CosFileStorage} vuelve a ser el
 * unico candidato del puerto.
 *
 * <p>Las anotaciones funcionan porque la llamada entra desde fuera (controller -> mediador
 * -> handler -> puerto) y atraviesa el proxy; no hay auto-invocacion que las anule.
 */
@Component
@Primary
@ConditionalOnProperty(name = "cache.enabled", havingValue = "true", matchIfMissing = true)
public class CachedFileStorage implements FileStorage {

    /**
     * Clave de cache: {@code <bucket logico>/<key>}. El bucket va delante porque es lo que
     * recibe el puerto —traducirlo al fisico es cosa del delegado— y porque si algun dia hay
     * un segundo bucket, dos objetos con la misma key no pueden pisarse.
     */
    private static final String KEY = "#bucket + '/' + #key";

    private final FileStorage delegate;

    public CachedFileStorage(@Qualifier(CosFileStorage.BEAN_NAME) FileStorage delegate) {
        this.delegate = delegate;
    }

    /**
     * {@code sync = true} colapsa las peticiones concurrentes al mismo archivo en una sola
     * bajada del COS, en vez de que N peticiones simultaneas provoquen N descargas.
     *
     * <p>Cuidado al tocar esto: con {@code sync = true} Spring prohibe {@code unless} y
     * declarar varias caches, y no lo detecta al compilar sino al ejecutar, con un 500.
     */
    @Override
    @Cacheable(cacheNames = CacheConfig.CONTENT_CACHE, key = KEY, sync = true)
    public StoredFile download(String bucket, String key) {
        return delegate.download(bucket, key);
    }

    /** Subir pisa el objeto: la entrada cacheada queda obsoleta y hay que tirarla. */
    @Override
    @CacheEvict(cacheNames = CacheConfig.CONTENT_CACHE, key = KEY)
    public StoredObject upload(String bucket, String key, byte[] content, String contentType) {
        return delegate.upload(bucket, key, content, contentType);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CONTENT_CACHE, key = KEY)
    public void delete(String bucket, String key) {
        delegate.delete(bucket, key);
    }

    /**
     * Sin cachear a proposito: responde por la existencia real del objeto y es barata
     * (una HEAD, no el binario). Cachearla haria que un archivo recien borrado por fuera
     * del servicio siguiera figurando como presente.
     */
    @Override
    public boolean exists(String bucket, String key) {
        return delegate.exists(bucket, key);
    }
}
