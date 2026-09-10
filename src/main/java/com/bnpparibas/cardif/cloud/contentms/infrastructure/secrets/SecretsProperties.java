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
 * @param url            endpoint de la instancia, por ejemplo
 *                       {@code https://<instance-id>.us-south.secrets-manager.appdomain.cloud}.
 *                       En local, el stub de WireMock del compose
 * @param iamUrl         endpoint de IAM que emite el token. Admite tanto la forma base
 *                       ({@code https://iam.cloud.ibm.com}) como la completa
 *                       ({@code .../identity/token}): el SDK normaliza las dos
 * @param apiKey         API key de IBM Cloud con la que se autentica la lectura del secreto.
 *                       Es el <b>unico</b> secreto que sigue viajando como variable de
 *                       entorno: es la llave con la que se abre el resto, asi que no puede
 *                       estar dentro de lo que abre. Ver secret-manager.md §4
 * @param name           nombre del secreto {@code kv} a leer
 * @param group          grupo de secretos que lo contiene
 * @param redis          el service credential de Redis, que es un secreto APARTE. Ver
 *                       {@link RedisSecret}
 */
public record SecretsProperties(
        Boolean enabled,
        String url,
        String iamUrl,
        String apiKey,
        String name,
        String group,
        RedisSecret redis
) {

    /** El tipo del secreto principal, el que trae las credenciales del COS. */
    public static final String SECRET_TYPE_KV = "kv";

    /**
     * El tipo del secreto de Redis. Lo genera IBM al enlazar la instancia, asi que su forma
     * la fija el servicio y hay que navegarla; ver {@link IbmRedisCredentialsSource} y
     * credenciales-ibm-cloud.md.
     */
    public static final String SECRET_TYPE_SERVICE_CREDENTIALS = "service_credentials";

    public SecretsProperties {
        enabled = enabled != null && enabled;
        group = group == null || group.isBlank() ? "default" : group;
        // El grupo del secreto de Redis cae al del secreto principal si no se declara aparte:
        // en la practica los dos viven en el mismo grupo, y repetirlo en cada perfil solo da
        // margen a que se desincronicen. Que se lea o no NO se decide aqui, sino en
        // cache.enabled: sin cache no hay conexion a Redis que configurar.
        redis = redis == null
                ? new RedisSecret(null, group)
                : redis.withGroupFallback(group);
    }

    /**
     * El service credential de Redis.
     *
     * <p>Es un secreto <b>distinto</b> del {@code kv}, y no por gusto: un
     * {@code service_credentials} pertenece a una unica instancia enlazada, asi que las
     * credenciales del COS y las de Redis no pueden compartir secreto. Eso obliga a dos
     * lecturas, porque la API v2 no tiene operacion de lote para valores (solo
     * {@code GET /api/v2/secrets}, que devuelve metadatos). Ver credenciales-ibm-cloud.md.
     *
     * <p>No tiene interruptor propio: <b>lo manda {@code cache.enabled}</b>. Sin cache no hay
     * conexion a Redis que configurar, y con cache encendida este secreto es obligatorio. Dos
     * flags para lo mismo solo daban margen a que se contradijeran, y la combinacion mala
     * (cache encendida, secreto apagado) era justo la que producia el fallo silencioso.
     *
     * @param name      nombre del secreto
     * @param group     grupo que lo contiene. Vacio = el mismo que el del secreto principal
     */
    public record RedisSecret(
            String name,
            String group
    ) {

        /**
         * Nombre del SSL bundle donde se registra la CA que viene dentro del secreto.
         *
         * <p>Es una constante y no una propiedad: hay exactamente un bundle en el servicio, asi
         * que configurarlo seria un mando que nadie toca. Ver {@link IbmRedisCredentialsSource}.
         */
        public static final String SSL_BUNDLE = "contentms-redis";

        RedisSecret withGroupFallback(String fallback) {
            return group == null || group.isBlank()
                    ? new RedisSecret(name, fallback)
                    : this;
        }
    }
}
