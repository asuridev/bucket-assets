package com.bnpparibas.cardif.cloud.contentms.domain.values;

/**
 * De donde sale el valor asociado a un id. Puerto, con un adaptador real
 * ({@code RandomValueGenerator}) y un decorador de cache encima
 * ({@code CachedValueGenerator}).
 *
 * <p>Que la cache decore <b>este puerto</b> y no el caso de uso es lo que mantiene a
 * {@code domain} y {@code application} sin una sola linea sobre Redis: el handler pide el valor
 * y no sabe si vino de la cache o se acaba de generar. Es la misma decision que en la rama
 * principal, donde el decorador envuelve el puerto de almacenamiento.
 *
 * <p>El contrato tiene una propiedad que conviene tener presente porque es la que se esta
 * validando: <b>para un mismo id, dos llamadas seguidas deben devolver lo mismo</b>. El
 * adaptador real no lo cumple —genera un valor nuevo cada vez— y es la cache la que lo hace
 * cierto. Por eso dos GET con el mismo id que devuelvan valores distintos significan,
 * exactamente, que la cache no esta funcionando.
 */
public interface ValueGenerator {

    /**
     * @param id identificador por el que se cachea
     * @return el valor asociado a ese id. Nunca {@code null}
     */
    String valueFor(String id);
}
