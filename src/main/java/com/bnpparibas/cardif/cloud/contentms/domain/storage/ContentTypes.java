package com.bnpparibas.cardif.cloud.contentms.domain.storage;

import java.util.Locale;
import java.util.Map;

/**
 * Decide con que MIME se guarda un archivo.
 *
 * <p><b>Manda la extension del {@code fileName}</b>, no lo que declare el cliente. La
 * razon es que el archivo se sirve por su clave, y la clave la compone ese mismo
 * {@code fileName} ({@link ObjectKey#of(String, String)}): si el metadato dijera
 * {@code image/png} para un {@code .svg}, el GET devolveria un SVG etiquetado como PNG y
 * el navegador no lo pintaria. Deduciendolo de la extension, metadato y clave concuerdan
 * por construccion.
 *
 * <p>Lo declarado por el cliente queda como respaldo para extensiones que no conocemos:
 * asi, anadir un MIME nuevo a {@code allowed-content-types} sigue funcionando aunque
 * nadie se acuerde de tocar este mapa.
 *
 * <p>Esto NO es una defensa: no se miran los bytes del archivo, asi que un PDF renombrado
 * a {@code .png} se guardara como {@code image/png}. Es coherencia, no seguridad.
 */
public final class ContentTypes {

    /** Cuando no se puede deducir nada. La politica del bucket lo rechazara con un 422. */
    public static final String DEFAULT = "application/octet-stream";

    /** Extensiones que conocemos: las de los MIME que admite el bucket de CMS. */
    private static final Map<String, String> BY_EXTENSION = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "svg", "image/svg+xml",
            "pdf", "application/pdf");

    private ContentTypes() {
        // Clase de utilidad.
    }

    /**
     * @param fileName            nombre con el que se guarda el archivo; su extension decide
     * @param declaredContentType MIME que declaro el cliente; solo se usa si la extension
     *                            es desconocida
     * @return el MIME con el que guardar, o {@link #DEFAULT} si no hay forma de saberlo
     */
    public static String resolve(String fileName, String declaredContentType) {
        String byExtension = BY_EXTENSION.get(extensionOf(fileName));
        if (byExtension != null) {
            return byExtension;
        }
        if (declaredContentType == null || declaredContentType.isBlank()) {
            return DEFAULT;
        }
        String declared = declaredContentType.trim().toLowerCase(Locale.ROOT);
        // octet-stream es lo que mandan los clientes que no declaran nada: no aporta.
        return DEFAULT.equals(declared) ? DEFAULT : declared;
    }

    /** Extension en minusculas y sin punto, o cadena vacia si no tiene. */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }
}
