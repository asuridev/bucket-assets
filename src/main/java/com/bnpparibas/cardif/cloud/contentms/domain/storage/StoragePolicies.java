package com.bnpparibas.cardif.cloud.contentms.domain.storage;

/**
 * Acceso a la politica declarada de cada bucket. La implementacion es el record de
 * {@code @ConfigurationProperties} de infraestructura; el dominio y la aplicacion
 * solo dependen de esta interfaz.
 */
public interface StoragePolicies {

    /** Bucket privado del COS de CMS donde vive el contenido estatico (HU-211). */
    String CMS_CONTENT = "cmsContent";

    /**
     * Politica del bucket, por su nombre logico.
     *
     * @throws IllegalStateException si la configuracion no lo declara: es un fallo de
     *         despliegue, no una condicion de negocio
     */
    BucketPolicy forBucket(String name);
}
