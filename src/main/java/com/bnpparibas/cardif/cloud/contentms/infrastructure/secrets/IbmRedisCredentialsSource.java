package com.bnpparibas.cardif.cloud.contentms.infrastructure.secrets;

import com.bnpparibas.cardif.cloud.contentms.infrastructure.secrets.SecretsProperties.RedisSecret;
import com.ibm.cloud.secrets_manager_sdk.secrets_manager.v2.model.GetSecretByNameTypeOptions;
import com.ibm.cloud.secrets_manager_sdk.secrets_manager.v2.model.Secret;
import com.ibm.cloud.secrets_manager_sdk.secrets_manager.v2.model.ServiceCredentialsSecretCredentials;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Lee el service credential de Redis y lo traduce a propiedades de Spring.
 *
 * <p>La diferencia de fondo con {@link IbmSecretsManagerSource} no es el tipo de secreto,
 * es <b>quien decide la forma de lo que hay dentro</b>. Un {@code kv} lo escribimos nosotros
 * y por eso sus claves ya se llaman como las variables de los YAML. Un
 * {@code service_credentials} lo genera IBM al enlazar la instancia: la estructura la fija el
 * servicio, esta anidada, y hay que navegarla y mapearla a mano. Ver
 * credenciales-ibm-cloud.md.
 *
 * <h2>Lo que trae, y por que importa</h2>
 *
 * <p>Una credencial de conexion a Redis <b>no es usuario y password</b>. El payload real
 * ({@code service-credentials/serviceCredentialsRedis.json}) trae ademas esquema
 * {@code rediss} (TLS obligatorio), un usuario de ACL, la base de datos y una CA
 * <b>autofirmada</b> en base64 que la JVM no conoce. De los ocho campos, tres no tenian
 * sitio en la configuracion de este servicio antes de esta clase.
 *
 * <h2>El certificado va por propiedades, no por codigo</h2>
 *
 * <p>Spring Boot admite SSL bundles con el PEM <b>en linea</b>, y sabe aplicarselos a Redis
 * via {@code spring.data.redis.ssl.bundle}. Eso permite resolver el TLS entero desde aqui,
 * registrando propiedades, sin declarar un {@code RedisConnectionFactory}, sin un
 * {@code LettuceClientConfigurationBuilderCustomizer} y sin construir un {@code KeyStore} a
 * mano: {@code CacheConfig} no sabe que esto existe. Es la misma filosofia que el {@code kv}.
 *
 * <p>No hay interruptor propio para esto: lo manda {@code cache.enabled}. Sin cache no hay
 * conexion a Redis que configurar, y con cache encendida este secreto es obligatorio -- es el
 * unico sitio del que salen host, puerto, usuario, password y TLS.
 */
final class IbmRedisCredentialsSource implements SecretsSource {

    /**
     * Claves candidatas dentro de {@code connection}, en orden de preferencia. IBM la nombra
     * por el esquema, asi que una instancia con TLS la publica como {@code rediss}; se admite
     * {@code redis} para no fallar contra una sin TLS. El proyecto de referencia usa
     * {@code mongodb} para Mongo por el mismo criterio.
     */
    private static final List<String> CONNECTION_KEYS = List.of("rediss", "redis");

    private final SecretsProperties properties;

    IbmRedisCredentialsSource(SecretsProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Object> fetch() {
        RedisSecret redis = properties.redis();
        SecretsManagerClients.require(redis.name(), "secrets.redis.name");

        Secret secret;
        try {
            secret = SecretsManagerClients.create(properties)
                    .getSecretByNameType(new GetSecretByNameTypeOptions.Builder()
                            .secretType(SecretsProperties.SECRET_TYPE_SERVICE_CREDENTIALS)
                            .name(redis.name())
                            .secretGroupName(redis.group())
                            .build())
                    .execute()
                    .getResult();
        } catch (RuntimeException exception) {
            // El mensaje nombra la VARIABLE, no la propiedad: es lo que uno exporta de verdad.
            // Y nombra el stack local que sirve este secreto, que es el tropiezo mas probable
            // de quien se encuentre esto en local: sin el stub levantado no hay secreto.
            throw new IllegalStateException("No se pudo leer el service credential de Redis '"
                    + redis.name() + "' (grupo '" + redis.group() + "') de Secrets Manager en "
                    + properties.url()
                    + ". En local lo sirve el stack de infra/ (cd infra && ./up.sh redis"
                    + " ibm-secret-manager). Para arrancar sin cache: CACHE_ENABLED=false",
                    exception);
        }

        if (!SecretsProperties.SECRET_TYPE_SERVICE_CREDENTIALS.equals(secret.getSecretType())) {
            throw new IllegalStateException("El secreto '" + redis.name() + "' es de tipo '"
                    + secret.getSecretType() + "'; secrets.redis.* espera uno de tipo '"
                    + SecretsProperties.SECRET_TYPE_SERVICE_CREDENTIALS + "'");
        }

        return propertiesFrom(connection(secret, redis), redis);
    }

    /**
     * Baja hasta {@code credentials.connection.<esquema>}.
     *
     * <p>{@link ServiceCredentialsSecretCredentials} es un {@code DynamicModel}: sus campos
     * tipados son los de un service credential de COS ({@code apikey},
     * {@code iam_role_crn}...), y todo lo demas —{@code connection} incluido— llega como
     * propiedad dinamica. De ahi el {@code get()} en vez de un getter.
     *
     * <p><b>Que {@code credentials} cuelgue de la RAIZ del secreto no es una suposicion.</b> En
     * el modelo del SDK ese campo <b>no lleva {@code @SerializedName}</b> —a diferencia de
     * {@code sourceService}, que si lo lleva con {@code "source_service"}—, asi que Gson lo mapea
     * por el nombre del campo: la clave JSON es {@code credentials}. Comprobado ademas
     * deserializando con el Gson del propio SDK: con el envoltorio, {@code getCredentials()}
     * devuelve el objeto y {@code connection} aparece entre sus propiedades dinamicas; sin el,
     * devuelve {@code null}. Y como el SDK se genera de la misma especificacion OpenAPI que
     * implementa el servicio, ese es el contrato de la API v2.
     *
     * <p>Lo que <b>no</b> fija Secrets Manager es la forma de AHI PARA DENTRO
     * ({@code connection.rediss.*}): eso lo decide el servicio enlazado, y para el SDK es un
     * objeto libre. Por eso el resto de este metodo valida y falla con mensajes que dicen que
     * falta, en vez de confiar.
     */
    private Map<String, Object> connection(Secret secret, RedisSecret redis) {
        ServiceCredentialsSecretCredentials credentials = secret.getCredentials();
        if (credentials == null) {
            throw new IllegalStateException("El secreto '" + redis.name()
                    + "' no trae 'credentials'. Es de tipo service_credentials, asi que"
                    + " deberia: revisa que la instancia enlazada haya emitido la credencial");
        }

        Map<String, Object> byScheme = asMap(credentials.get("connection"),
                "credentials.connection", redis);

        for (String key : CONNECTION_KEYS) {
            Object candidate = byScheme.get(key);
            if (candidate != null) {
                return asMap(candidate, "credentials.connection." + key, redis);
            }
        }
        // Un NullPointerException aqui no diria nada. Las claves SI se pueden loguear: son
        // nombres de esquema, no credenciales.
        throw new IllegalStateException("El secreto '" + redis.name() + "' no trae ninguna de"
                + " las conexiones esperadas " + CONNECTION_KEYS + " dentro de"
                + " 'credentials.connection'. Lo que trae es " + new TreeSet<>(byScheme.keySet())
                + ": es un service credential de otro servicio?");
    }

    /** El mapeo, que es la unica razon de ser de esta clase. */
    private Map<String, Object> propertiesFrom(Map<String, Object> connection, RedisSecret redis) {
        Map<String, Object> host = firstHost(connection, redis);
        Map<String, Object> authentication = asMap(connection.get("authentication"),
                "authentication", redis);

        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("spring.data.redis.host", required(host.get("hostname"), "hostname", redis));
        mapped.put("spring.data.redis.port",
                wholeNumber(required(host.get("port"), "port", redis)));
        mapped.put("spring.data.redis.username",
                required(authentication.get("username"), "authentication.username", redis));
        mapped.put("spring.data.redis.password",
                required(authentication.get("password"), "authentication.password", redis));

        Object database = connection.get("database");
        if (database != null) {
            mapped.put("spring.data.redis.database", wholeNumber(String.valueOf(database)));
        }

        // El TLS lo decide el esquema que declara el propio secreto, no una propiedad nuestra:
        // asi una instancia sin TLS se configura sola y no hay dos sitios que contradecirse.
        boolean tls = CONNECTION_KEYS.get(0).equals(String.valueOf(connection.get("scheme")));
        mapped.put("spring.data.redis.ssl.enabled", String.valueOf(tls));

        if (tls) {
            mapped.put("spring.data.redis.ssl.bundle", RedisSecret.SSL_BUNDLE);
            mapped.put("spring.ssl.bundle.pem." + RedisSecret.SSL_BUNDLE
                    + ".truststore.certificate", certificate(connection, redis));
        }
        return mapped;
    }

    /**
     * {@code hosts} es una lista. La instancia de Redis trae un elemento, pero nada lo
     * garantiza: si trajera mas seria un cluster, y esta configuracion apuntaria a un solo
     * nodo. Se avisa en vez de elegir en silencio.
     */
    private Map<String, Object> firstHost(Map<String, Object> connection, RedisSecret redis) {
        Object raw = connection.get("hosts");
        if (!(raw instanceof List<?> hosts) || hosts.isEmpty()) {
            throw new IllegalStateException("El service credential '" + redis.name()
                    + "' no trae 'hosts', o viene vacio: sin host no hay a donde conectar");
        }
        if (hosts.size() > 1) {
            throw new IllegalStateException("El service credential '" + redis.name() + "' trae "
                    + hosts.size() + " hosts, y esta configuracion solo sabe apuntar a uno."
                    + " Si la instancia es un cluster, hace falta configurar Lettuce en modo"
                    + " cluster, no elegir un nodo en silencio");
        }
        return asMap(hosts.get(0), "hosts[0]", redis);
    }

    /**
     * {@code certificate_base64} es el base64 <b>del PEM</b>, no del DER: al descodificarlo
     * sale directamente el texto {@code -----BEGIN CERTIFICATE-----}, que es lo que espera un
     * bundle PEM en linea.
     */
    private String certificate(Map<String, Object> connection, RedisSecret redis) {
        Map<String, Object> certificate = asMap(connection.get("certificate"), "certificate",
                redis);
        String base64 = String.valueOf(required(certificate.get("certificate_base64"),
                "certificate.certificate_base64", redis));

        String pem;
        try {
            pem = new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("El 'certificate_base64' del secreto '"
                    + redis.name() + "' no es base64 valido", exception);
        }

        if (!pem.contains("-----BEGIN CERTIFICATE-----")) {
            // Sin esto, el fallo aparece mucho mas tarde y disfrazado: el bundle no carga, el
            // handshake falla, y CacheConfig.errorHandler() lo degrada a un WARN. La cache
            // no cachearia nada mientras todo responde 200.
            throw new IllegalStateException("El 'certificate_base64' del secreto '"
                    + redis.name() + "' no descodifica a un PEM: no contiene"
                    + " '-----BEGIN CERTIFICATE-----'. Se esperaba el base64 del PEM, no del DER");
        }
        return pem;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value, String path, RedisSecret redis) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("En el secreto '" + redis.name() + "', '" + path
                    + "' no es un objeto" + (value == null ? " (falta)" : ""));
        }
        return (Map<String, Object>) map;
    }

    /**
     * Todo se registra como {@code String}: es lo que el {@code Binder} de Spring espera de
     * una fuente de propiedades, y ahorra tener que adivinar el tipo que devolvio Gson (que
     * para los numeros no es {@code Integer}, sino {@code LazilyParsedNumber} o
     * {@code Double} segun la version).
     */
    private String required(Object value, String path, RedisSecret redis) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("En el secreto '" + redis.name() + "' falta '"
                    + path + "', y es obligatorio para construir la conexion a Redis");
        }
        return String.valueOf(value);
    }

    /**
     * Quita el {@code .0} que deja Gson cuando deserializa un entero de un JSON generico como
     * {@code Double}. Sin esto, el puerto llegaria al {@code Binder} como "6380.0" y el
     * arranque fallaria al convertirlo a {@code int}, con un mensaje que no mencionaria a
     * Secrets Manager para nada.
     */
    private static String wholeNumber(String value) {
        return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
    }
}
