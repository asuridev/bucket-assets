# Stub de IBM Cloud Secrets Manager

WireMock haciendo de instancia de Secrets Manager para el perfil `local`. Existe para que
en local se recorra **el mismo camino de código** que en Code Engine —el SDK real, el token
de IAM, la llamada HTTP, el parseo de la respuesta y el registro del `PropertySource`— sin
credenciales de IBM Cloud. Si el parseo está mal, el fallo se ve aquí y no en el primer
despliegue.

Es exactamente el mismo criterio con el que este repo usa MinIO en vez de COS: un solo
adaptador, y lo único que cambia es contra quién habla.

Lo levanta `deploy/docker-compose.yaml` en el puerto **8090**.

## Los dos mappings

| Fichero | Qué responde |
|---|---|
| `iam-token.json` | `POST /identity/token`. El `IamAuthenticator` pide el token **antes** que el secreto; sin este mapping el error que se ve no menciona a Secrets Manager |
| `secret-kv.json` | `GET /api/v2/secret_groups/default/secret_types/kv/secrets/contentms-secrets`, la ruta exacta que construye `getSecretByNameType` |

Si cambias `SECRETS_NAME` o `SECRETS_GROUP`, hay que cambiar también el `urlPath` de
`secret-kv.json`: WireMock casa por ruta literal.

## Aquí es donde se setean los secretos en local

El objeto `data` de `secret-kv.json` es el equivalente local del secreto `kv` de IBM Cloud:
el mismo JSON, en otro sitio. Es **el único sitio** donde se ponen las credenciales cuando se
trabaja en local.

```jsonc
"data": {
  "MINIO_ACCESS_KEY": "minioadmin",   // <- las usa de verdad el servicio para firmar
  "MINIO_SECRET_KEY": "minioadmin",   //    contra MinIO
  "COS_API_KEY": "stub-cos-api-key",  // <- presentes para que la forma del secreto sea
  "COS_SERVICE_INSTANCE_ID": "...",   //    la misma que en develop/production
  "REDIS_PASSWORD": ""
}
```

Se editan y se reinicia el contenedor:

```bash
podman restart contentms-secrets-stub
# y reinicia el servicio: el secreto se lee una sola vez, al arrancar
```

**Comprobar que el secreto se está usando de verdad**: pon un valor incorrecto en
`MINIO_SECRET_KEY`, reinicia el stub y el servicio, y sube un archivo. Tiene que fallar:

```
HTTP 503  {"errorDetail":{"code":"STORAGE_UNAVAILABLE", ...}}
```

Devuelve el valor bueno y vuelve a dar 201. Nada más cambió: ni una variable de entorno, ni
una línea de código.

`MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` tienen que coincidir con las del contenedor de MinIO,
que salen de las variables del compose (`minioadmin`/`minioadmin` por defecto). Los valores de
`COS_*` no los usa nadie en local —el perfil `local` firma HMAC contra MinIO, no IAM contra el
COS— y están para que la forma del secreto sea idéntica a la de los demás entornos. Si quieres
apuntar en local a un COS real, pon aquí sus valores y cambia `storage.auth-mode` a `iam`.

## Ensayar el modo `container`

El servicio puede autenticarse contra Secrets Manager de dos formas (`secrets.auth-mode`): con
una API key, o con el token que la plataforma monta en el pod (`container`). El segundo no
necesita Code Engine para probarse aquí: el SDK canjea ese token en el **mismo**
`POST /identity/token` que este stub ya sirve, y la ruta del fichero es configurable. Por eso
el fichero `cr-token` de este directorio, que hace de token del pod.

```bash
SECRETS_AUTH_MODE=container \
SECRETS_CR_TOKEN_FILE=deploy/secrets-manager-stub/cr-token \
SECRETS_IAM_PROFILE_NAME=contentms-sm-reader \
java -jar target/content-ms-1.0.0.jar --spring.profiles.active=local
```

El arranque tiene que ser idéntico al del modo por defecto. Que el canje fue de verdad el del
modo `container` se comprueba en el diario de peticiones:

```bash
curl -s 'http://localhost:8090/__admin/requests?limit=2' | grep grant_type
# ...grant-type%3Acr-token&cr_token=...&profile_name=contentms-sm-reader   <- container
# ...grant-type%3Aapikey&apikey=local-fake-api-key                          <- apikey
```

El mapping `iam-token.json` casa **solo por método y ruta**, sin mirar el cuerpo, y por eso
sirve a los dos modos. No le añadas un matcher de body: romperías este ensayo.

## Comprobar que se está usando

```bash
podman logs contentms-secrets-stub
```

Tienen que aparecer el `POST /identity/token` y el `GET /api/v2/secret_groups/...`. En el
arranque del servicio, la traza `Secrets Manager: N claves cargadas ...`.
