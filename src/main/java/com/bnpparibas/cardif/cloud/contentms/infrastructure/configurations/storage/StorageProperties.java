package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.storage;

import com.bnpparibas.cardif.cloud.contentms.domain.storage.BucketPolicy;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoragePolicies;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de almacenamiento, y a la vez adaptador del puerto
 * {@link StoragePolicies}: la propia config responde a "cual es la politica de este
 * bucket", asi que no hace falta una clase intermedia.
 *
 * @param authMode          {@code iam} (COS real) o {@code hmac} (MinIO en local).
 *                          El SDK de IBM admite ambas: la primera con
 *                          {@code BasicIBMOAuthCredentials}, la segunda con las
 *                          credenciales S3 de toda la vida
 * @param endpoint          endpoint del COS (por ejemplo
 *                          {@code https://s3.us-south.cloud-object-storage.appdomain.cloud})
 *                          o el de MinIO ({@code http://localhost:9000})
 * @param location          region con la que se firma
 * @param apiKey            API key de IAM; solo con {@code authMode: iam}
 * @param serviceInstanceId CRN de la instancia de COS; solo con {@code authMode: iam}
 * @param accessKey         access key HMAC; solo con {@code authMode: hmac}
 * @param secretKey         secret key HMAC; solo con {@code authMode: hmac}
 * @param requestTimeoutMs  timeout por peticion al COS
 * @param buckets           buckets declarados, por nombre logico
 */
@ConfigurationProperties("storage")
public record StorageProperties(
        String authMode,
        String endpoint,
        String location,
        String apiKey,
        String serviceInstanceId,
        String accessKey,
        String secretKey,
        Integer requestTimeoutMs,
        Map<String, BucketProperties> buckets
) implements StoragePolicies {

    public static final String AUTH_IAM = "iam";
    public static final String AUTH_HMAC = "hmac";

    /**
     * @param bucket              nombre fisico en el proveedor
     * @param visibility          documental: en este servicio siempre {@code private}
     * @param maxSizeMb           tamano maximo admitido; null = sin limite
     * @param allowedContentTypes MIME admitidos; vacio = sin restriccion
     */
    public record BucketProperties(
            String bucket,
            String visibility,
            Integer maxSizeMb,
            List<String> allowedContentTypes
    ) {
    }

    public StorageProperties {
        buckets = buckets == null ? Map.of() : Map.copyOf(buckets);
        authMode = authMode == null || authMode.isBlank() ? AUTH_IAM : authMode.trim().toLowerCase();
    }

    public boolean isHmacAuth() {
        return AUTH_HMAC.equals(authMode);
    }

    @Override
    public BucketPolicy forBucket(String name) {
        BucketProperties properties = buckets.get(name);
        if (properties == null) {
            throw new IllegalStateException("La configuracion no declara el bucket '" + name
                    + "'. Declaralo en storage.buckets." + name + " del perfil activo.");
        }
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new IllegalStateException("El bucket '" + name
                    + "' no declara nombre fisico (storage.buckets." + name + ".bucket).");
        }
        return new BucketPolicy(name, properties.bucket(), properties.maxSizeMb(),
                properties.allowedContentTypes());
    }
}
