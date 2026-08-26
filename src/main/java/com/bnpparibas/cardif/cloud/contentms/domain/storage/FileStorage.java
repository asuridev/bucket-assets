package com.bnpparibas.cardif.cloud.contentms.domain.storage;

/**
 * Puerto de almacenamiento de archivos. La implementacion vive en
 * {@code infrastructure.storage}; el dominio solo depende de esta interfaz.
 *
 * <p>El parametro {@code bucket} es el nombre LOGICO del diseno (las constantes de
 * {@link StoragePolicies}), no el fisico del proveedor: traducirlo es cosa del
 * adaptador.
 *
 * <p>A diferencia de un puerto sobre buckets publicos, aqui hay {@link #download}
 * y no hay {@code publicUrl}: el bucket del CMS es privado, asi que nadie lee el
 * objeto por URL — se sirve por este servicio.
 */
public interface FileStorage {

    /** Sube el binario y devuelve como quedo almacenado. */
    StoredObject upload(String bucket, String key, byte[] content, String contentType);

    /**
     * Descarga el binario.
     *
     * @throws com.bnpparibas.cardif.cloud.contentms.domain.errors.FileNotFoundError
     *         si la clave no existe en el bucket
     */
    StoredFile download(String bucket, String key);

    boolean exists(String bucket, String key);

    void delete(String bucket, String key);
}
