package com.bnpparibas.cardif.cloud.contentms.infrastructure.secrets;

import com.ibm.cloud.sdk.core.security.Authenticator;
import com.ibm.cloud.sdk.core.security.ContainerAuthenticator;
import com.ibm.cloud.sdk.core.security.IamAuthenticator;
import com.ibm.cloud.secrets_manager_sdk.secrets_manager.v2.SecretsManager;
import com.ibm.cloud.secrets_manager_sdk.secrets_manager.v2.model.GetSecretByNameTypeOptions;
import com.ibm.cloud.secrets_manager_sdk.secrets_manager.v2.model.Secret;
import java.util.Map;

/**
 * Lee un secreto de tipo {@code kv} de IBM Cloud Secrets Manager.
 *
 * <p>El flujo es el del SDK: se consigue un token de IAM y sobre esa autenticacion se pide
 * el secreto por nombre, grupo y tipo. Es la misma llamada que hace el proyecto de
 * referencia ({@code GetCredentialsCommand}), con dos diferencias deliberadas: aqui el tipo
 * es {@code kv} y no {@code service_credentials} — porque lo que se guarda son credenciales
 * de dos servicios distintos (COS y Redis), no las de una instancia enlazada — y el
 * resultado no se mapea a un objeto tipado, sino que se vuelca como propiedades (ver
 * {@link SecretsEnvironmentPostProcessor}).
 *
 * <h2>Los dos modos de autenticacion</h2>
 *
 * <p>Lo unico que cambia entre ellos es <b>como se consigue ese token</b>; de ahi en
 * adelante el codigo es identico.
 *
 * <ul>
 *   <li><b>{@code apikey}</b> (el default): un {@link IamAuthenticator} cambia una API key
 *       de IBM Cloud por un token IAM. Es lo que ya funcionaba, y lo unico que se puede
 *       usar en local contra credenciales de verdad.</li>
 *   <li><b>{@code container}</b>: un {@link ContainerAuthenticator} lee del disco el token
 *       que la plataforma monta en el pod y lo canjea en IAM contra un trusted profile.
 *       <b>No hay ninguna credencial en el despliegue</b>: la identidad se la da la
 *       plataforma. Elimina el problema del huevo y la gallina de secret-manager.md.</li>
 * </ul>
 *
 * <p>El modo es explicito y no hay fallback de uno a otro: ver secret-manager.md.
 *
 * <p>La URL de IAM es configurable, y eso es lo que hace posible el stub local: apuntando
 * {@code secrets.iam-url} y {@code secrets.url} al mismo WireMock, este mismo adaptador
 * funciona sin credenciales reales de IBM Cloud, en cualquiera de los dos modos.
 */
final class IbmSecretsManagerSource implements SecretsSource {

    private final SecretsProperties properties;

    IbmSecretsManagerSource(SecretsProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Object> fetch() {
        require(properties.url(), "secrets.url");
        require(properties.name(), "secrets.name");
        requireCredentials();

        Secret secret;
        try {
            secret = client().getSecretByNameType(new GetSecretByNameTypeOptions.Builder()
                            .secretType(SecretsProperties.SECRET_TYPE_KV)
                            .name(properties.name())
                            .secretGroupName(properties.group())
                            .build())
                    .execute()
                    .getResult();
        } catch (RuntimeException exception) {
            // Sin mensaje propio, lo que se ve en el arranque es un stack del SDK sin decir
            // contra que instancia se estaba hablando ni con que identidad.
            throw new IllegalStateException("No se pudo leer el secreto '" + properties.name()
                    + "' (grupo '" + properties.group() + "') de Secrets Manager en "
                    + properties.url() + " con autenticacion '" + properties.authMode() + "'",
                    exception);
        }

        if (!SecretsProperties.SECRET_TYPE_KV.equals(secret.getSecretType())) {
            throw new IllegalStateException("El secreto '" + properties.name() + "' es de tipo '"
                    + secret.getSecretType() + "'; este servicio solo lee secretos de tipo '"
                    + SecretsProperties.SECRET_TYPE_KV + "'. Ver secret-manager.md");
        }

        Map<String, Object> data = secret.getData();
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("El secreto '" + properties.name()
                    + "' no tiene claves. Arrancar con el resolveria a nada y el fallo"
                    + " apareceria mas tarde, al conectar con el COS");
        }
        return data;
    }

    /**
     * Se usa el constructor publico y no {@code SecretsManager.newInstance()} a proposito:
     * el segundo aplica configuracion externa (variables {@code SECRETS_MANAGER_*} y
     * ficheros de credenciales del SDK) que se pisaria con la de aqui sin dejar rastro.
     */
    private SecretsManager client() {
        SecretsManager client = new SecretsManager(SecretsManager.DEFAULT_SERVICE_NAME,
                authenticator());
        client.setServiceUrl(properties.url());
        return client;
    }

    private Authenticator authenticator() {
        return properties.isContainerAuth() ? containerAuthenticator() : apiKeyAuthenticator();
    }

    private Authenticator apiKeyAuthenticator() {
        IamAuthenticator.Builder builder = new IamAuthenticator.Builder()
                .apikey(properties.apiKey());
        if (hasText(properties.iamUrl())) {
            builder.url(properties.iamUrl());
        }
        return builder.build();
    }

    /**
     * El token del pod. {@code crTokenFilename} se deja sin fijar salvo que venga
     * configurado: el SDK prueba entonces sus tres rutas por defecto, y una de ellas es
     * justo la que monta Code Engine. Poder fijarla es lo que permite ensayar este modo en
     * local con un token de mentira, sin plataforma.
     */
    private Authenticator containerAuthenticator() {
        ContainerAuthenticator.Builder builder = new ContainerAuthenticator.Builder()
                .iamProfileName(emptyToNull(properties.iamProfileName()))
                .iamProfileId(emptyToNull(properties.iamProfileId()));
        if (hasText(properties.crTokenFilename())) {
            builder.crTokenFilename(properties.crTokenFilename());
        }
        if (hasText(properties.iamUrl())) {
            builder.url(properties.iamUrl());
        }
        return builder.build();
    }

    /**
     * Que falte una credencial es un fallo de despliegue, y el mensaje tiene que decir en
     * que modo se esta y que variable falta. El SDK tambien valida, pero su mensaje habla de
     * propiedades del builder, no de la configuracion de este servicio.
     */
    private void requireCredentials() {
        if (properties.isApiKeyAuth()) {
            require(properties.apiKey(), "secrets.api-key");
            return;
        }
        if (properties.isContainerAuth()) {
            if (!hasText(properties.iamProfileName()) && !hasText(properties.iamProfileId())) {
                throw new IllegalStateException("Con secrets.auth-mode=container hace falta"
                        + " secrets.iam-profile-name o secrets.iam-profile-id: es el trusted"
                        + " profile contra el que se canjea el token del pod");
            }
            return;
        }
        throw new IllegalStateException("secrets.auth-mode='" + properties.authMode()
                + "' no existe; los modos validos son '" + SecretsProperties.AUTH_APIKEY
                + "' y '" + SecretsProperties.AUTH_CONTAINER + "'");
    }

    private void require(String value, String property) {
        if (!hasText(value)) {
            throw new IllegalStateException("Falta " + property
                    + ": es obligatorio con secrets.enabled=true y secrets.auth-mode="
                    + properties.authMode());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** El Binder rellena con cadena vacia lo que el YAML declara como {@code ${VAR:}}. */
    private static String emptyToNull(String value) {
        return hasText(value) ? value : null;
    }
}
