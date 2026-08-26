package com.bnpparibas.cardif.cloud.contentms.application.dtos;

/**
 * Cuerpo del 201 de {@code POST /v1/save-content}, tal como lo tabula el HU-211:
 * {@code Body {responseHeader}} con {@code returnCode} y {@code Message}.
 *
 * <p>Ambos son string en el contrato, no numeros: el HU los declara asi
 * ({@code Codigo Respuesta "201"}), y cambiarlos a int romperia al consumidor.
 *
 * @param returnCode codigo de respuesta, "201"
 * @param message    mensaje de respuesta, "Created"
 */
public record SaveContentResponseDto(String returnCode, String message) {

    public static SaveContentResponseDto created() {
        return new SaveContentResponseDto("201", "Created");
    }
}
