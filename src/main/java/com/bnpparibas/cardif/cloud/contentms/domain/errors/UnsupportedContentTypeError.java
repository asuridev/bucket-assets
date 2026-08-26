package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * El MIME del archivo no esta entre los admitidos por el bucket.
 */
public class UnsupportedContentTypeError extends BusinessException {

    public static final String CODE = "UNSUPPORTED_CONTENT_TYPE";

    public UnsupportedContentTypeError(String message) {
        super(message, CODE);
    }
}
