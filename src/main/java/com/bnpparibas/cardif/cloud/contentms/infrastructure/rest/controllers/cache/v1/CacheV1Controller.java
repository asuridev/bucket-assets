package com.bnpparibas.cardif.cloud.contentms.infrastructure.rest.controllers.cache.v1;

import com.bnpparibas.cardif.cloud.contentms.application.queries.GetCachedValueQuery;
import com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.usecase.UseCaseMediator;
import com.bnpparibas.cardif.cloud.contentms.infrastructure.correlation.CorrelationIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El unico endpoint de esta rama.
 *
 * <p>Devuelve un valor aleatorio asociado al id, y lo cachea. <b>Dos llamadas con el mismo id
 * tienen que devolver lo mismo</b>; con ids distintos, valores distintos. Esa igualdad es toda
 * la prueba: si falla, la cache no esta funcionando, sin necesidad de mirar Redis.
 *
 * <p>El controller depende solo del {@link UseCaseMediator}, nunca del handler, igual que en la
 * rama principal.
 *
 * <p>{@code correlation_id} y {@code request_id} se admiten como OPCIONALES, con el mismo
 * criterio que alli: si no llegan, la peticion responde igual; si llegan, tienen que ser un
 * UUID canonico (400 si no) y trazan las lineas de log de esa peticion.
 */
@RestController
@Validated
@RequestMapping("/v1")
@Tag(name = "Cache", description = "Endpoint de validacion de la cache de Redis.")
public class CacheV1Controller {

    private final UseCaseMediator mediator;

    public CacheV1Controller(UseCaseMediator mediator) {
        this.mediator = mediator;
    }

    @Operation(summary = "Devuelve un valor aleatorio para el id, cacheado. Repetir el mismo id"
            + " tiene que devolver el mismo valor.")
    @GetMapping(value = "/cache/{id}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getCachedValue(
            @Parameter(description = "Message correlation UUID. Opcional; si se envia,"
                    + " debe ser un UUID valido")
            @RequestHeader(value = "correlation_id", required = false) String correlationId,
            @Parameter(description = "Unique UUID to identify the resource. Opcional; si se"
                    + " envia, debe ser un UUID valido")
            @RequestHeader(value = "request_id", required = false) String requestId,
            @Parameter(description = "Identificador por el que se cachea. Cualquier cadena no vacia")
            @PathVariable("id") @NotBlank String id) {

        // Opcionales, pero no cualquier cosa: un identificador de trazado malformado no traza
        // nada y contamina la correlacion aguas abajo. El eco en la respuesta lo pone el
        // CorrelationFilter, no este metodo.
        CorrelationIds.optionalUuid("correlation_id", correlationId);
        CorrelationIds.optionalUuid("request_id", requestId);

        return ResponseEntity.ok(mediator.dispatch(new GetCachedValueQuery(id)));
    }
}
