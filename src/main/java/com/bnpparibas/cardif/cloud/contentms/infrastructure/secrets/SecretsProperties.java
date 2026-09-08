package com.bnpparibas.cardif.cloud.contentms.infrastructure.secrets;

/**
 * Configuracion del acceso a IBM Cloud Secrets Manager.
 *
 * <p>A diferencia de {@code StorageProperties} o {@code CacheProperties}, este record
 * <b>no lleva {@code @ConfigurationProperties} ni es un bean</b>: quien lo usa es
 * {@link SecretsEnvironmentPostProcessor}, que corre antes de que exista el contexto de
 * Spring. Se enlaza a mano con un {@code Binder} sobre el {@code Environment} ya cargado.
 *
 * @param enabled        si se consulta Secrets Manager al arrancar. Con {@code false} el
 *                       servicio se comporta exactamente como antes de esta funcionalidad:
 *                       los secretos tienen que llegar como variables de entorno. Mismo
 *                       patron que {@code cache.enabled}
 * @param authMode       {@code apikey} (una API key de IBM Cloud) o {@code container} (el
 *                       token que la plataforma monta en el pod). Ver
 *                       {@link IbmSecretsManagerSource}
 * @param url            endpoint de la instancia, por ejemplo
 *                       {@code https://<instance-id>.us-south.secrets-manager.appdomain.cloud}.
 *                       En local, el stub de WireMock del compose
 * @param iamUrl         endpoint de IAM que emite el token. Admite tanto la forma base
 *                       ({@code https://iam.cloud.ibm.com}) como la completa
 *                       ({@code .../identity/token}): el SDK normaliza las dos. Lo usan los
 *                       dos modos
 * @param apiKey         API key de IBM Cloud con la que se autentica la lectura del secreto.
 *                       <b>Solo en modo {@code apikey}</b>, y ahi es el unico secreto que
 *                       sigue viajando como variable de entorno: es la llave con la que se
 *                       abre el resto. En modo {@code container} no existe
 * @param iamProfileName nombre del trusted profile con el que se canjea el token del pod.
 *                       Solo en modo {@code container}; alternativa a {@code iamProfileId}
 * @param iamProfileId   id del trusted profile. Solo en modo {@code container}; alternativa
 *                       a {@code iamProfileName}
 * @param crTokenFilename fichero del que leer el token del pod. Solo en modo
 *                       {@code container}, y opcional: vacio deja que el SDK pruebe sus tres
 *                       rutas por defecto, una de las cuales es la de Code Engine. Se expone
 *                       para poder ensayar el modo en local con un token de mentira
 * @param name           nombre del secreto a leer
 * @param group          grupo de secretos que lo contiene
 */
public record SecretsProperties(
        Boolean enabled,
        String authMode,
        String url,
        String iamUrl,
        String apiKey,
        String iamProfileName,
        String iamProfileId,
        String crTokenFilename,
        String name,
        String group
) {

    /** El unico tipo de secreto que este adaptador sabe leer. Ver secret-manager.md. */
    public static final String SECRET_TYPE_KV = "kv";

    /** Autenticacion con una API key de IBM Cloud. El default, y lo que ya funcionaba. */
    public static final String AUTH_APIKEY = "apikey";

    /**
     * Autenticacion con el token que la plataforma monta en el pod, canjeado contra un
     * trusted profile. No hay ninguna credencial en el despliegue.
     */
    public static final String AUTH_CONTAINER = "container";

    public SecretsProperties {
        enabled = enabled != null && enabled;
        group = group == null || group.isBlank() ? "default" : group;
        // Mismo tratamiento que StorageProperties.authMode: normalizar aqui evita repetir
        // trim/toLowerCase en cada comparacion, y el default conservador es el modo que ya
        // funcionaba antes de existir el conmutador.
        authMode = authMode == null || authMode.isBlank()
                ? AUTH_APIKEY
                : authMode.trim().toLowerCase();
    }

    public boolean isContainerAuth() {
        return AUTH_CONTAINER.equals(authMode);
    }

    public boolean isApiKeyAuth() {
        return AUTH_APIKEY.equals(authMode);
    }
}
