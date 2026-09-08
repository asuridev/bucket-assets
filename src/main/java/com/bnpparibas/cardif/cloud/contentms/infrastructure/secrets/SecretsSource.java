package com.bnpparibas.cardif.cloud.contentms.infrastructure.secrets;

import java.util.Map;

/**
 * De donde salen los secretos del servicio. Puerto, con un unico adaptador
 * ({@link IbmSecretsManagerSource}).
 *
 * <p>Existe la interfaz y no una clase suelta por el mismo motivo por el que existe
 * {@code FileStorage}: deja el punto de sustitucion escrito. Aun asi, para desarrollar en
 * local <b>no se cambia de adaptador</b>, se cambia de destino — el stub HTTP del compose
 * habla la misma API v2 — de modo que en local se ejercita el mismo camino de codigo que
 * corre en Code Engine.
 */
public interface SecretsSource {

    /**
     * @return las claves del secreto, tal cual estan guardadas. Nunca {@code null}
     * @throws IllegalStateException si el secreto no se puede leer o no tiene la forma
     *                               esperada. Es un fallo de despliegue: mejor no arrancar
     */
    Map<String, Object> fetch();
}
