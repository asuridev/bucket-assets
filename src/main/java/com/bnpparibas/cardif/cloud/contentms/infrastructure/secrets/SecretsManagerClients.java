package com.bnpparibas.cardif.cloud.contentms.infrastructure.secrets;

import com.ibm.cloud.sdk.core.security.IamAuthenticator;
import com.ibm.cloud.secrets_manager_sdk.secrets_manager.v2.SecretsManager;

/**
 * Construye el cliente de Secrets Manager y valida lo minimo para poder usarlo.
 *
 * <p>Existe porque hay <b>dos</b> origenes de secretos ({@link IbmSecretsManagerSource} para
 * el {@code kv} del COS y {@link IbmRedisCredentialsSource} para el service credential de
 * Redis) y los dos necesitan exactamente el mismo cliente.
 *
 * <p>La autenticacion es siempre la misma: un {@link IamAuthenticator} cambia la API key de
 * IBM Cloud por un token IAM, y lo cachea y renueva por su cuenta. Esa API key es la unica
 * credencial que sigue viajando como variable de entorno — es la llave con la que se abren las
 * demas, asi que no puede estar dentro de lo que abre. Ver secret-manager.md §4.
 *
 * <p>La URL de IAM es configurable, y eso es lo que hace posible el stub local: apuntando
 * {@code secrets.iam-url} y {@code secrets.url} al mismo WireMock, este mismo codigo funciona
 * sin credenciales reales de IBM Cloud.
 */
final class SecretsManagerClients {

    private SecretsManagerClients() {
    }

    /**
     * Cliente listo para usar, con la configuracion minima ya validada.
     *
     * @throws IllegalStateException si falta la URL de la instancia o la API key. Es un fallo
     *                               de despliegue: mejor no arrancar
     */
    static SecretsManager create(SecretsProperties properties) {
        require(properties.url(), "secrets.url");
        require(properties.apiKey(), "secrets.api-key");

        // Se usa el constructor publico y no SecretsManager.newInstance() a proposito: el
        // segundo aplica configuracion externa (variables SECRETS_MANAGER_* y ficheros de
        // credenciales del SDK) que se pisaria con la de aqui sin dejar rastro.
        SecretsManager client = new SecretsManager(SecretsManager.DEFAULT_SERVICE_NAME,
                authenticator(properties));
        client.setServiceUrl(properties.url());
        return client;
    }

    private static IamAuthenticator authenticator(SecretsProperties properties) {
        IamAuthenticator.Builder builder = new IamAuthenticator.Builder()
                .apikey(properties.apiKey());
        if (hasText(properties.iamUrl())) {
            builder.url(properties.iamUrl());
        }
        return builder.build();
    }

    /**
     * Que falte una credencial es un fallo de despliegue, y el mensaje tiene que decir que
     * propiedad falta. El SDK tambien valida, pero su mensaje habla de propiedades del builder,
     * no de la configuracion de este servicio.
     */
    static void require(String value, String property) {
        if (!hasText(value)) {
            throw new IllegalStateException("Falta " + property
                    + ": es obligatorio con secrets.enabled=true");
        }
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
