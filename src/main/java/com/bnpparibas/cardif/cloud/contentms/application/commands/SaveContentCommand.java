package com.bnpparibas.cardif.cloud.contentms.application.commands;

import com.bnpparibas.cardif.cloud.contentms.application.dtos.FileUpload;
import com.bnpparibas.cardif.cloud.contentms.application.dtos.SaveContentResponseDto;
import com.bnpparibas.cardif.cloud.contentms.application.interfaces.ReturningCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Guarda un archivo de contenido estatico en el COS de CMS bajo
 * {@code /partnerId/Object}.
 *
 * <p>El {@code partnerId} llega ya resuelto por el controller (del JSON o, si falta,
 * del header {@code _p}): el caso de uso lo recibe obligatorio porque la clave del
 * objeto no se puede componer sin el.
 *
 * @param fileName nombre con el que se guarda el archivo
 * @param partnerId id de socio, ya resuelto
 * @param file el binario
 */
public record SaveContentCommand(
        @NotBlank String fileName,
        @NotBlank String partnerId,
        @NotNull FileUpload file
) implements ReturningCommand<SaveContentResponseDto> {
}
