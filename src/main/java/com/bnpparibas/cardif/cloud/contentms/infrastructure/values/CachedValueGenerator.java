package com.bnpparibas.cardif.cloud.contentms.infrastructure.values;

import com.bnpparibas.cardif.cloud.contentms.domain.values.ValueGenerator;
import com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.cache.CacheConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Decorador de cache sobre {@link ValueGenerator}. Es lo unico que esta rama existe para
 * validar.
 *
 * <p>El {@code @Primary} hace que el caso de uso reciba esta version sin saberlo. Con
 * {@code cache.enabled: false} este bean no existe y {@link RandomValueGenerator} vuelve a ser
 * el unico candidato del puerto — y entonces cada peticion devuelve un valor distinto, que es
 * la forma mas rapida de comprobar que el decorador estaba haciendo algo.
 *
 * <p>Las anotaciones funcionan porque la llamada entra desde fuera (controller -&gt; mediador
 * -&gt; handler -&gt; puerto) y atraviesa el proxy; no hay auto-invocacion que las anule.
 *
 * <p><b>Y aqui esta el detalle que hace util esta rama:</b> si Redis no responde —TLS mal,
 * credencial equivocada, instancia caida— {@code CacheConfig.errorHandler()} degrada el fallo a
 * un WARN y la peticion sigue hasta el generador. El endpoint responde 200 igualmente, pero
 * <b>con un valor distinto cada vez</b>. En la rama principal ese mismo fallo es invisible
 * desde fuera (el binario se sirve del COS y nadie nota nada); aqui se ve en la propia
 * respuesta.
 */
@Component
@Primary
@ConditionalOnProperty(name = "cache.enabled", havingValue = "true", matchIfMissing = true)
public class CachedValueGenerator implements ValueGenerator {

    private final ValueGenerator delegate;

    public CachedValueGenerator(@Qualifier(RandomValueGenerator.BEAN_NAME) ValueGenerator delegate) {
        this.delegate = delegate;
    }

    /**
     * {@code sync = true} colapsa las peticiones concurrentes al mismo id en una sola
     * generacion, igual que en la rama principal colapsa las descargas del mismo fichero.
     *
     * <p>Cuidado al tocarlo: con {@code sync = true} Spring prohibe {@code unless} y declarar
     * varias caches, y no lo detecta al compilar sino al ejecutar, con un 500.
     */
    @Override
    @Cacheable(cacheNames = CacheConfig.VALUE_CACHE, key = "#id", sync = true)
    public String valueFor(String id) {
        return delegate.valueFor(id);
    }
}
