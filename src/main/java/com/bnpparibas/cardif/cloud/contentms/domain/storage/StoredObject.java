package com.bnpparibas.cardif.cloud.contentms.domain.storage;

/**
 * Descripcion de un binario ya almacenado. Value object inmutable: lo devuelve el
 * puerto al subir.
 *
 * @param storageKey  clave del objeto en el proveedor; es la que identifica el
 *                    binario para descargarlo o borrarlo
 * @param bucket      nombre fisico del bucket donde quedo
 * @param contentType MIME del binario (por ejemplo image/png)
 * @param sizeBytes   tamano en bytes
 */
public record StoredObject(String storageKey, String bucket, String contentType, long sizeBytes) {
}
