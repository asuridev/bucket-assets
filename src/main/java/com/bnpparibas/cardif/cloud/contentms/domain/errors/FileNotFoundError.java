package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * El archivo pedido no esta en el bucket de CMS.
 */
public class FileNotFoundError extends NotFoundException {

    public static final String CODE = "CONTENT_NOT_FOUND";

    public FileNotFoundError(String message) {
        super(message, CODE);
    }
}
