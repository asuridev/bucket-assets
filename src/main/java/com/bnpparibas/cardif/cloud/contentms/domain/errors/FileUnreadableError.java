package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * No se pudo leer el binario que llego en el multipart.
 */
public class FileUnreadableError extends BadRequestException {

    public static final String CODE = "FILE_UNREADABLE";

    public FileUnreadableError(String message) {
        super(message, CODE);
    }
}
