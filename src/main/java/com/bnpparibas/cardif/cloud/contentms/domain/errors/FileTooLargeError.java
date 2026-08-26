package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * El archivo supera el tamano maximo declarado para el bucket.
 */
public class FileTooLargeError extends PayloadTooLargeException {

    public static final String CODE = "FILE_TOO_LARGE";

    public FileTooLargeError(String message) {
        super(message, CODE);
    }
}
