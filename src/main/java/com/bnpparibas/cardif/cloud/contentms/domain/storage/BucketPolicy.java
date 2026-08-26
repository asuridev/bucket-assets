package com.bnpparibas.cardif.cloud.contentms.domain.storage;

import java.util.List;
import java.util.Locale;

/**
 * Politica declarada para un bucket en la configuracion. Value object inmutable: lo
 * entrega {@link StoragePolicies} y lo consulta el caso de uso antes de subir.
 *
 * @param name                nombre logico del bucket
 * @param bucket              nombre fisico en el proveedor
 * @param maxSizeMb           tamano maximo admitido, o null si no se acota
 * @param allowedContentTypes MIME admitidos; vacio significa "sin restriccion"
 */
public record BucketPolicy(String name, String bucket, Integer maxSizeMb, List<String> allowedContentTypes) {

    public BucketPolicy {
        allowedContentTypes = allowedContentTypes == null
                ? List.of()
                : allowedContentTypes.stream().map(type -> type.trim().toLowerCase(Locale.ROOT)).toList();
    }

    /** Esta admitido el MIME? Sin tipos declarados no hay restriccion que aplicar. */
    public boolean allowsContentType(String contentType) {
        return allowedContentTypes.isEmpty()
                || (contentType != null && allowedContentTypes.contains(contentType.toLowerCase(Locale.ROOT)));
    }

    /** Cabe el tamano? Sin limite declarado, siempre. */
    public boolean allowsSize(long sizeBytes) {
        return maxSizeMb == null || sizeBytes <= (long) maxSizeMb * 1024L * 1024L;
    }
}
