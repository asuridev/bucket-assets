package com.bnpparibas.cardif.cloud.contentms.application.queries;

import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoredFile;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.Query;
import jakarta.validation.constraints.NotBlank;

/**
 * Consulta el archivo migrado a partir de su {@code context_url}, que ya identifica al
 * socio y al archivo: es la unica entrada del GET.
 *
 * @param contextUrl {@code <partnerId>/<archivo>}, por ejemplo {@code 12345/image1.png}
 */
public record GetContentLoadedQuery(
        @NotBlank String contextUrl
) implements Query<StoredFile> {
}
