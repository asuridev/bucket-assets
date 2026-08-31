package com.bnpparibas.cardif.cloud.contentms.application.usecases;

import com.bnpparibas.cardif.cloud.contentms.application.annotations.ApplicationComponent;
import com.bnpparibas.cardif.cloud.contentms.application.annotations.LogExceptions;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.QueryHandler;
import com.bnpparibas.cardif.cloud.contentms.application.queries.GetContentLoadedQuery;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.FileStorage;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.ObjectKey;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoragePolicies;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoredFile;

/**
 * Devuelve el archivo migrado como binario.
 *
 * <p>El HU-211 describe tres pasos: mirar en la cache de Redis, ir al COS si no esta, y
 * repoblar la cache. Los tres ocurren, pero ninguno se ve aqui: viven en
 * {@code CachedFileStorage}, un decorador del puerto {@link FileStorage}. Por eso este
 * metodo se limita a pedir el archivo — la cache es una decision de infraestructura y este
 * caso de uso no tiene por que saber que existe.
 */
@ApplicationComponent
public class GetContentLoadedQueryHandler implements QueryHandler<GetContentLoadedQuery, StoredFile> {

    private final FileStorage fileStorage;

    public GetContentLoadedQueryHandler(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    @LogExceptions
    public StoredFile handle(GetContentLoadedQuery query) {
        String key = ObjectKey.ofContextUrl(query.contextUrl());
        return fileStorage.download(StoragePolicies.CMS_CONTENT, key);
    }
}
