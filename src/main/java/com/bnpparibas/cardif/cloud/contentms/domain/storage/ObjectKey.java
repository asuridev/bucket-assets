package com.bnpparibas.cardif.cloud.contentms.domain.storage;

import com.bnpparibas.cardif.cloud.contentms.domain.errors.BadRequestException;

/**
 * Composicion de la clave del objeto en el COS.
 *
 * <p>El HU-211 describe la estructura del bucket como {@code partnerId --> Object}:
 * cada socio tiene su prefijo y el archivo cuelga de el. Esta clase es el unico sitio
 * donde se materializa esa regla, para que subir y descargar no puedan divergir.
 *
 * <p><b>Por que valida:</b> el {@code context_url} del GET viene del cliente y ya trae
 * el prefijo del socio dentro. Sin comprobarlo, un {@code ../otroSocio/secreto.pdf} se
 * normalizaria fuera de ese prefijo y dejaria leer cualquier cosa del bucket, incluso
 * fuera del espacio de socios.
 */
public final class ObjectKey {

    public static final String CODE_INVALID_PATH = "INVALID_OBJECT_PATH";

    private ObjectKey() {
        // Clase de utilidad.
    }

    /**
     * {@code <partnerId>/<path>}, con el path saneado.
     *
     * @throws BadRequestException si el path intenta salirse del prefijo del socio
     */
    public static String of(String partnerId, String path) {
        return requirePartnerId(partnerId) + "/" + sanitize(path);
    }

    /**
     * Misma clave, pero a partir del {@code context_url} del GET, que ya trae el socio
     * delante: {@code 12345/image1.png}. El prefijo hasta la primera barra es el
     * partnerId y el resto es la ruta del archivo.
     *
     * <p>Delega en {@link #of(String, String)} a proposito: asi las dos entradas
     * (POST y GET) pasan por las mismas comprobaciones y no pueden divergir.
     *
     * @throws BadRequestException si falta el prefijo del socio o si la ruta intenta
     *                             salirse de el
     */
    public static String ofContextUrl(String contextUrl) {
        if (contextUrl == null || contextUrl.isBlank()) {
            throw new BadRequestException("El context_url es obligatorio", CODE_INVALID_PATH);
        }

        String normalized = contextUrl.replace('\\', '/').trim();
        int separator = normalized.indexOf('/');
        if (separator <= 0 || separator == normalized.length() - 1) {
            throw new BadRequestException(
                    "El context_url debe tener la forma <partnerId>/<archivo>, por ejemplo"
                            + " 12345/image1.png: " + contextUrl,
                    CODE_INVALID_PATH);
        }

        return of(normalized.substring(0, separator), normalized.substring(separator + 1));
    }

    private static String requirePartnerId(String partnerId) {
        if (partnerId == null || partnerId.isBlank()) {
            throw new BadRequestException("El partnerId es obligatorio para componer la clave del objeto",
                    CODE_INVALID_PATH);
        }
        if (partnerId.contains("/") || partnerId.contains("\\") || partnerId.contains("..")) {
            throw new BadRequestException("El partnerId no puede contener separadores de ruta",
                    CODE_INVALID_PATH);
        }
        return partnerId.trim();
    }

    /**
     * Deja el path en algo que no puede escapar del prefijo: sin barras iniciales,
     * sin backslashes de Windows, sin segmentos {@code .} ni {@code ..}, sin bytes
     * nulos y sin segmentos vacios por barras repetidas.
     */
    private static String sanitize(String path) {
        if (path == null || path.isBlank()) {
            throw new BadRequestException("La ruta del archivo es obligatoria", CODE_INVALID_PATH);
        }
        if (path.indexOf('\0') >= 0) {
            throw new BadRequestException("La ruta del archivo contiene caracteres no validos",
                    CODE_INVALID_PATH);
        }

        String normalized = path.replace('\\', '/').trim();
        StringBuilder result = new StringBuilder();
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new BadRequestException(
                        "La ruta del archivo no puede salir del espacio del socio: " + path,
                        CODE_INVALID_PATH);
            }
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(segment);
        }

        if (result.isEmpty()) {
            throw new BadRequestException("La ruta del archivo esta vacia tras normalizarla: " + path,
                    CODE_INVALID_PATH);
        }
        return result.toString();
    }

    /** Ultimo segmento de la clave: el nombre con el que se sirve el archivo. */
    public static String fileNameOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }
}
