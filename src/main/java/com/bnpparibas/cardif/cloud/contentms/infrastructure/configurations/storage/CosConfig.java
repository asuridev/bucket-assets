package com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.storage;

import com.ibm.cloud.objectstorage.ClientConfiguration;
import com.ibm.cloud.objectstorage.auth.AWSCredentials;
import com.ibm.cloud.objectstorage.auth.AWSStaticCredentialsProvider;
import com.ibm.cloud.objectstorage.auth.BasicAWSCredentials;
import com.ibm.cloud.objectstorage.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.ibm.cloud.objectstorage.oauth.BasicIBMOAuthCredentials;
import com.ibm.cloud.objectstorage.services.s3.AmazonS3;
import com.ibm.cloud.objectstorage.services.s3.AmazonS3ClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cliente de IBM Cloud Object Storage, autenticado por IAM.
 *
 * <p>Es el SDK de IBM ({@code com.ibm.cloud.objectstorage}), no el de AWS: aunque la
 * API es la del AWS SDK v1, solo este trae {@link BasicIBMOAuthCredentials}, que
 * cambia la API key por un token IAM y lo renueva sola.
 *
 * <p>Se construye siempre: no hay almacen alternativo. Lo que cambia por entorno es el
 * endpoint y el modo de firma ({@code storage.auth-mode}), de modo que el MinIO local
 * y el COS real recorren exactamente el mismo codigo.
 */
@Configuration
public class CosConfig {

    @Bean
    public AmazonS3 cosClient(StorageProperties properties) {
        requireConfigured(properties.endpoint(), "storage.endpoint");

        AWSCredentials credentials = credentialsFor(properties);

        ClientConfiguration clientConfiguration = new ClientConfiguration()
                .withRequestTimeout(properties.requestTimeoutMs() == null
                        ? 15_000
                        : properties.requestTimeoutMs());
        clientConfiguration.setUseTcpKeepAlive(true);

        return AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withEndpointConfiguration(new EndpointConfiguration(
                        properties.endpoint(),
                        properties.location() == null ? "us-south" : properties.location()))
                .withPathStyleAccessEnabled(true)
                .withClientConfiguration(clientConfiguration)
                .build();
    }

    /**
     * IAM contra el COS real; HMAC contra MinIO en local.
     *
     * <p>Ambas salen del mismo SDK: {@code BasicIBMOAuthCredentials} cambia la API key
     * por un token IAM y lo renueva sola, mientras que {@code BasicAWSCredentials} firma
     * con AWS4, que es lo unico que MinIO entiende. El adaptador
     * {@code CosFileStorage} no se entera de la diferencia — por eso probar en local
     * ejercita el mismo codigo que corre en produccion.
     */
    private static AWSCredentials credentialsFor(StorageProperties properties) {
        if (properties.isHmacAuth()) {
            requireConfigured(properties.accessKey(), "storage.access-key");
            requireConfigured(properties.secretKey(), "storage.secret-key");
            return new BasicAWSCredentials(properties.accessKey(), properties.secretKey());
        }
        requireConfigured(properties.apiKey(), "storage.api-key");
        requireConfigured(properties.serviceInstanceId(), "storage.service-instance-id");
        return new BasicIBMOAuthCredentials(properties.apiKey(), properties.serviceInstanceId());
    }

    /** Un fallo aqui es de despliegue: mejor no arrancar que arrancar sin poder guardar nada. */
    private static void requireConfigured(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta " + property
                    + ": es obligatorio para conectar con el object storage");
        }
    }
}
