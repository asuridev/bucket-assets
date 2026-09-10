package com.bnpparibas.cardif.cloud.contentms.application.queries;

import com.bnpparibas.cardif.cloud.contentms.application.interfaces.Query;
import jakarta.validation.constraints.NotBlank;

/**
 * Pide el valor asociado a un id. El id es lo unico que entra, y es la clave de cache.
 */
public record GetCachedValueQuery(
        @NotBlank String id
) implements Query<String> {
}
