package com.bnpparibas.cardif.cloud.contentms.infrastructure.storage;

import com.bnpparibas.cardif.cloud.contentms.domain.errors.FileNotFoundError;
import com.bnpparibas.cardif.cloud.contentms.domain.errors.StorageUnavailableError;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.FileStorage;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.ObjectKey;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoragePolicies;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoredFile;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoredObject;
import com.ibm.cloud.objectstorage.SdkClientException;
import com.ibm.cloud.objectstorage.services.s3.AmazonS3;
import com.ibm.cloud.objectstorage.services.s3.model.AmazonS3Exception;
import com.ibm.cloud.objectstorage.services.s3.model.ObjectMetadata;
import com.ibm.cloud.objectstorage.services.s3.model.PutObjectRequest;
import com.ibm.cloud.objectstorage.services.s3.model.S3Object;
import com.ibm.cloud.objectstorage.services.s3.model.S3ObjectInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptador del puerto {@link FileStorage} contra IBM Cloud Object Storage.
 *
 * <p>Es el unico adaptador que habla con el proveedor: todos los perfiles usan un object
 * storage de verdad. {@link CachedFileStorage} tambien implementa el puerto, pero no es un
 * segundo almacen — es un decorador que acaba delegando aqui.
 *
 * <p>Lo que cambia por entorno es contra quien y como se firma
 * ({@code storage.auth-mode}: IAM contra el COS real, HMAC contra el MinIO local),
 * nunca la ruta de codigo.
 *
 * <p>Traduce el nombre LOGICO del bucket al fisico via {@link StoragePolicies}, y los
 * fallos del SDK a errores tipados del dominio: un 404 del COS es
 * {@link FileNotFoundError} (condicion de negocio, 404 al cliente), cualquier otro
 * fallo de red o del proveedor es {@link StorageUnavailableError} (503, reintentable).
 */
@Component(CosFileStorage.BEAN_NAME)
public class CosFileStorage implements FileStorage {

    /**
     * Nombre explicito del bean. Desde que existe {@link CachedFileStorage} hay dos
     * implementaciones del puerto, y el decorador tiene que pedir ESTA por su nombre para
     * no inyectarse a si mismo.
     */
    public static final String BEAN_NAME = "cosFileStorage";

    private static final Logger log = LoggerFactory.getLogger(CosFileStorage.class);

    private static final int HTTP_NOT_FOUND = 404;
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final AmazonS3 cosClient;
    private final StoragePolicies policies;

    public CosFileStorage(AmazonS3 cosClient, StoragePolicies policies) {
        this.cosClient = cosClient;
        this.policies = policies;
    }

    @Override
    public StoredObject upload(String bucket, String key, byte[] content, String contentType) {
        String physicalBucket = policies.forBucket(bucket).bucket();
        String resolvedContentType = contentType == null || contentType.isBlank()
                ? DEFAULT_CONTENT_TYPE
                : contentType;

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(resolvedContentType);
        metadata.setContentLength(content.length);

        try (InputStream source = new ByteArrayInputStream(content)) {
            cosClient.putObject(new PutObjectRequest(physicalBucket, key, source, metadata));
        } catch (IOException | SdkClientException exception) {
            throw new StorageUnavailableError(
                    "No se pudo guardar el objeto " + key + " en el bucket " + physicalBucket, exception);
        }

        log.debug("Objeto {} guardado en {} ({} bytes)", key, physicalBucket, content.length);
        return new StoredObject(key, physicalBucket, resolvedContentType, content.length);
    }

    @Override
    public StoredFile download(String bucket, String key) {
        String physicalBucket = policies.forBucket(bucket).bucket();

        // try-with-resources sobre el S3Object: el SDK v1 mantiene abierta la conexion
        // HTTP hasta que se cierra el stream, y no cerrarlo agota el pool de conexiones.
        try (S3Object object = cosClient.getObject(physicalBucket, key);
                S3ObjectInputStream content = object.getObjectContent()) {
            byte[] bytes = content.readAllBytes();
            ObjectMetadata metadata = object.getObjectMetadata();
            String contentType = metadata.getContentType() == null
                    ? DEFAULT_CONTENT_TYPE
                    : metadata.getContentType();
            return new StoredFile(bytes, contentType, bytes.length, ObjectKey.fileNameOf(key));
        } catch (AmazonS3Exception exception) {
            if (exception.getStatusCode() == HTTP_NOT_FOUND) {
                throw new FileNotFoundError("No existe el archivo " + key + " en el bucket de CMS");
            }
            throw new StorageUnavailableError(
                    "No se pudo leer el objeto " + key + " del bucket " + physicalBucket, exception);
        } catch (IOException | SdkClientException exception) {
            throw new StorageUnavailableError(
                    "No se pudo leer el objeto " + key + " del bucket " + physicalBucket, exception);
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        String physicalBucket = policies.forBucket(bucket).bucket();
        try {
            return cosClient.doesObjectExist(physicalBucket, key);
        } catch (SdkClientException exception) {
            throw new StorageUnavailableError(
                    "No se pudo comprobar el objeto " + key + " en el bucket " + physicalBucket, exception);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        String physicalBucket = policies.forBucket(bucket).bucket();
        try {
            cosClient.deleteObject(physicalBucket, key);
        } catch (SdkClientException exception) {
            throw new StorageUnavailableError(
                    "No se pudo borrar el objeto " + key + " del bucket " + physicalBucket, exception);
        }
    }
}
