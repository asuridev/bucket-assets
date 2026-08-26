package com.bnpparibas.cardif.cloud.contentms.domain.storage;

/**
 * Un binario recuperado del almacen, con lo que hace falta para servirlo por HTTP.
 *
 * <p>El contenido viaja en memoria como {@code byte[]}: el tamano ya viene acotado
 * por {@code storage.buckets.<n>.max-size-mb} y por el limite de multipart, asi que
 * no hay riesgo de cargar un objeto arbitrariamente grande. Si algun dia se admiten
 * archivos grandes, este es el punto donde cambiar a un stream.
 *
 * @param content     bytes del archivo
 * @param contentType MIME con el que se almaceno; puede ser null si el objeto se
 *                    subio sin declararlo
 * @param sizeBytes   tamano en bytes
 * @param fileName    nombre con el que se sirve al cliente
 */
public record StoredFile(byte[] content, String contentType, long sizeBytes, String fileName) {
}
