package com.bnpparibas.cardif.cloud.contentms.infrastructure.rest.controllers.content.v1;

import com.bnpparibas.cardif.cloud.contentms.application.commands.SaveContentCommand;
import com.bnpparibas.cardif.cloud.contentms.application.dtos.FileUpload;
import com.bnpparibas.cardif.cloud.contentms.application.dtos.SaveContentResponseDto;
import com.bnpparibas.cardif.cloud.contentms.application.queries.GetContentLoadedQuery;
import com.bnpparibas.cardif.cloud.contentms.domain.errors.FileUnreadableError;
import com.bnpparibas.cardif.cloud.contentms.domain.errors.InvalidJsonStringError;
import com.bnpparibas.cardif.cloud.contentms.domain.errors.PartnerIdRequiredError;
import com.bnpparibas.cardif.cloud.contentms.domain.storage.StoredFile;
import com.bnpparibas.cardif.cloud.contentms.infrastructure.configurations.usecase.UseCaseMediator;
import com.bnpparibas.cardif.cloud.contentms.infrastructure.correlation.CorrelationIds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * ContentMS: carga y consulta de archivos de contenido estatico en el COS de CMS.
 *
 * <p>Los tres headers del contrato ({@code correlation_id}, {@code request_id},
 * {@code _p}) son obligatorios <b>solo en el POST</b>; alli el
 * {@code CorrelationFilter} los pone en el contexto de log y los devuelve en la
 * respuesta.
 *
 * <p>En el GET, {@code context_url} sigue siendo la unica entrada obligatoria — ya lleva
 * el socio delante, y {@code _p} no existe alli. {@code correlation_id} y
 * {@code request_id} se admiten como OPCIONALES: si no llegan, la descarga responde igual
 * y la respuesta no los lleva; si llegan, tienen que ser un UUID canonico (400 si no) y se
 * devuelven tal cual, ademas de trazar las lineas de log de la peticion.
 */
@RestController
@Validated
@RequestMapping("/v1")
@Tag(name = "Content", description = "Contenido estatico del CMS: carga en COS y consulta binaria (HU-211).")
public class ContentV1Controller {

    private final UseCaseMediator mediator;
    private final ObjectMapper objectMapper;

    public ContentV1Controller(UseCaseMediator mediator, ObjectMapper objectMapper) {
        this.mediator = mediator;
        this.objectMapper = objectMapper;
    }

    /**
     * Campos del part {@code jsonString}, tal como los tabula el HU-211.
     *
     * @param fileName  nombre del archivo; obligatorio
     * @param partnerId id de socio; opcional — si falta se usa el header {@code _p}
     */
    public record SaveContentRequest(String fileName, String partnerId) {
    }

    @Operation(summary = "Guarda un archivo de contenido estatico en el COS de CMS bajo /partnerId/Object.")
    @PostMapping(value = "/save-content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SaveContentResponseDto> saveContent(
            @Parameter(description = "Message correlation UUID")
            @RequestHeader("correlation_id") @NotBlank String correlationId,
            @Parameter(description = "Unique UUID to identify the resource")
            @RequestHeader("request_id") @NotBlank String requestId,
            @Parameter(description = "Long partner ID")
            @RequestHeader("_p") @NotBlank String partnerHeader,
            @Parameter(description = "JSON con fileName y partnerId opcional")
            @RequestPart("jsonString") String jsonString,
            @Parameter(description = "Archivo binario")
            @RequestPart("file") MultipartFile file) {

        SaveContentRequest request = parse(jsonString);
        if (request.fileName() == null || request.fileName().isBlank()) {
            throw new InvalidJsonStringError("El campo 'fileName' es obligatorio en jsonString");
        }

        // El HU declara partnerId opcional en el JSON, pero la clave del objeto
        // (/partnerId/Object) lo exige: se cae al header _p, que si es obligatorio.
        String partnerId = firstNonBlank(request.partnerId(), partnerHeader);
        if (partnerId == null) {
            throw new PartnerIdRequiredError(
                    "No llego partnerId en jsonString ni en el header _p");
        }

        SaveContentResponseDto response = mediator.dispatch(
                new SaveContentCommand(request.fileName(), partnerId, toFileUpload(file)));

        return ResponseEntity.status(201).body(response);
    }

    @Operation(summary = "Devuelve el archivo migrado como binario, a partir de su context_url.")
    @GetMapping("/content-loaded")
    public ResponseEntity<byte[]> getContentLoaded(
            @Parameter(description = "Message correlation UUID. Opcional; si se envia,"
                    + " debe ser un UUID valido")
            @RequestHeader(value = "correlation_id", required = false) String correlationId,
            @Parameter(description = "Unique UUID to identify the resource. Opcional; si se"
                    + " envia, debe ser un UUID valido")
            @RequestHeader(value = "request_id", required = false) String requestId,
            @Parameter(description = "URL de contexto con el socio delante:"
                    + " <partnerId>/<archivo>, por ejemplo 12345/image1.png")
            @RequestParam("context_url") @NotBlank String contextUrl) {

        // Opcionales, pero no cualquier cosa: un identificador de trazado malformado no
        // traza nada y contamina la correlacion de extremo a extremo aguas abajo. El eco
        // en la respuesta lo pone el CorrelationFilter, no este metodo.
        CorrelationIds.optionalUuid("correlation_id", correlationId);
        CorrelationIds.optionalUuid("request_id", requestId);

        StoredFile file = mediator.dispatch(new GetContentLoadedQuery(contextUrl));

        // El HU tabula a la vez un responseHeader {returnCode, message} y el binario.
        // Un cuerpo HTTP solo admite una cosa: el binario va en el cuerpo y el
        // responseHeader viaja como headers, que es lo que preserva ambos.
        return ResponseEntity.ok()
                .header("returnCode", "200")
                .header("message", "OK")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .body(file.content());
    }

    private SaveContentRequest parse(String jsonString) {
        try {
            return objectMapper.readValue(jsonString, SaveContentRequest.class);
        } catch (JsonProcessingException exception) {
            throw new InvalidJsonStringError("El part 'jsonString' no es un JSON valido");
        }
    }

    private static FileUpload toFileUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileUnreadableError("El archivo enviado esta vacio");
        }
        try {
            return new FileUpload(file.getBytes(), file.getOriginalFilename(),
                    file.getContentType(), file.getSize());
        } catch (IOException exception) {
            throw new FileUnreadableError("No se pudo leer el archivo enviado");
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }
}
