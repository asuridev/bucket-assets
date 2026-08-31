package com.bnpparibas.cardif.cloud.contentms.application.usecases;

import com.bnpparibas.cardif.cloud.contentms.application.annotations.ApplicationComponent;
import com.bnpparibas.cardif.cloud.contentms.application.commands.SaveContentCommand;
import com.bnpparibas.cardif.cloud.contentms.application.dtos.FileUpload;
import com.bnpparibas.cardif.cloud.contentms.application.dtos.SaveContentResponseDto;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.ReturningCommandHandler;
import com.bnpparibas.cardif.cloud.contentms.domain.errors.FileTooLargeError;
import com.bnpparibas.cardif.cloud.contentms.domain.errors.UnsupportedContentTypeError;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.BucketPolicy;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.ContentTypes;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.FileStorage;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoragePolicies;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.ObjectKey;

/**
 * Guarda el archivo de contenido estatico en el COS de CMS.
 *
 * <p>Valida contra la politica del bucket ANTES de subir: enviar el binario al COS
 * para que lo rechace despues gasta una llamada de red y deja el error a merced de
 * como lo reporte el proveedor, en vez de darlo tipado desde aqui.
 */
@ApplicationComponent
public class SaveContentCommandHandler
        implements ReturningCommandHandler<SaveContentCommand, SaveContentResponseDto> {

    private final FileStorage fileStorage;
    private final StoragePolicies storagePolicies;

    public SaveContentCommandHandler(FileStorage fileStorage, StoragePolicies storagePolicies) {
        this.fileStorage = fileStorage;
        this.storagePolicies = storagePolicies;
    }

    @Override
    public SaveContentResponseDto handle(SaveContentCommand command) {
        FileUpload file = command.file();
        BucketPolicy policy = storagePolicies.forBucket(StoragePolicies.CMS_CONTENT);

        if (!policy.allowsSize(file.size())) {
            throw new FileTooLargeError("El archivo de " + file.size()
                    + " bytes supera el maximo de " + policy.maxSizeMb() + " MB del bucket "
                    + StoragePolicies.CMS_CONTENT);
        }
        // El MIME sale de la extension de fileName, no de lo que declare el cliente: es
        // ese mismo fileName el que compone la clave, asi que metadato y clave concuerdan.
        String contentType = ContentTypes.resolve(command.fileName(), file.contentType());
        if (!policy.allowsContentType(contentType)) {
            throw new UnsupportedContentTypeError("El tipo de contenido " + contentType
                    + " no esta admitido por el bucket " + StoragePolicies.CMS_CONTENT);
        }

        String key = ObjectKey.of(command.partnerId(), command.fileName());
        fileStorage.upload(StoragePolicies.CMS_CONTENT, key, file.content(), contentType);

        return SaveContentResponseDto.created();
    }
}
