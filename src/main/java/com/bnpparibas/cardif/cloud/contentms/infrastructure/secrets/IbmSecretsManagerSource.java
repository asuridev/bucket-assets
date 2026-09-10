package com.bnpparibas.cardif.cloud.contentms.infrastructure.secrets;

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
 * de dos servicios distintos (COS y MinIO), no las de una instancia enlazada — y el
 * resultado no se mapea a un objeto tipado, sino que se vuelca como propiedades (ver
 * {@link SecretsEnvironmentPostProcessor}).
 *
 * <p>Las claves de este secreto se llaman <b>igual que las variables de entorno que ya usan
 * los YAML</b>, asi que no hay mapeo ninguno: se devuelve {@code data} tal cual. Es lo que
 * distingue este origen de {@link IbmRedisCredentialsSource}, donde la forma la fija IBM y
 * hay que traducirla.
 *
 * <p>El cliente y los dos modos de autenticacion salen de {@link SecretsManagerClients}.
 */
final class IbmSecretsManagerSource implements SecretsSource {

    private final SecretsProperties properties;

    IbmSecretsManagerSource(SecretsProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Object> fetch() {
        SecretsManagerClients.require(properties.name(), "secrets.name");

        Secret secret;
        try {
            secret = SecretsManagerClients.create(properties)
                    .getSecretByNameType(new GetSecretByNameTypeOptions.Builder()
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
                    + properties.url(), exception);
        }

        if (!SecretsProperties.SECRET_TYPE_KV.equals(secret.getSecretType())) {
            throw new IllegalStateException("El secreto '" + properties.name() + "' es de tipo '"
                    + secret.getSecretType() + "'; aqui se espera uno de tipo '"
                    + SecretsProperties.SECRET_TYPE_KV + "'. Si lo que quieres es leer un"
                    + " service credential, eso es secrets.redis.*. Ver secret-manager.md");
        }

        Map<String, Object> data = secret.getData();
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("El secreto '" + properties.name()
                    + "' no tiene claves. Arrancar con el resolveria a nada y el fallo"
                    + " apareceria mas tarde, al conectar con el COS");
        }
        return data;
    }
}
