package com.bnpparibas.cardif.cloud.contentms.application.dtos;

/**
 * Un archivo tal como llego en el multipart, ya desligado de {@code MultipartFile}
 * para que la capa de aplicacion no dependa de Spring.
 *
 * @param content          bytes del archivo
 * @param originalFileName nombre con el que lo envio el cliente; puede ser null
 * @param contentType      MIME declarado por el cliente; puede ser null
 * @param size             tamano en bytes
 */
public record FileUpload(byte[] content, String originalFileName, String contentType, long size) {
}
