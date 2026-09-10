# Secretos con IBM Cloud Secrets Manager

Cómo obtiene ContentMS sus credenciales en IBM Cloud, y cómo se ensaya ese mismo mecanismo
en local sin tener una instancia.

---

## 1. El problema

Hasta ahora los secretos del servicio —la API key de IAM del COS, el CRN de la instancia,
la password de Redis— llegaban como **variables de entorno planas**. Los YAML de cada perfil
los leen así:

```yaml
# src/main/resources/parameters/develop/storage.yaml
storage:
  api-key: ${COS_API_KEY}
  service-instance-id: ${COS_SERVICE_INSTANCE_ID}
```

En Code Engine eso significa dejar la API key escrita en la configuración del proyecto: sin
rotación, sin auditoría de quién la lee, y visible para cualquiera con permiso de lectura
sobre la app. **IBM Cloud Secrets Manager** es el servicio gestionado que resuelve eso:
guarda el secreto cifrado, registra cada acceso y permite rotarlo sin tocar el despliegue.

## 2. Cómo funciona

Secrets Manager es una API REST (`/api/v2/...`) autenticada con IAM. Hay un SDK Java oficial
(`com.ibm.cloud:secrets-manager`) que la envuelve. El flujo tiene siempre dos saltos:

```
        API key de IBM Cloud (IBM_CLOUD_API_KEY)
                    |
                    v
   POST https://iam.cloud.ibm.com/identity/token        <- 1. IAM devuelve un token
                    |
                    v
   GET  https://<instancia>.<region>.secrets-manager.appdomain.cloud
        /api/v2/secret_groups/{grupo}/secret_types/kv/secrets/{nombre}
        Authorization: Bearer <token>                   <- 2. el secreto
```

El primer salto lo hace solo el `IamAuthenticator` del SDK, que además **cachea el token y lo
renueva** cuando le queda poco de vida. En código son cuatro líneas
(`IbmSecretsManagerSource`):

```java
SecretsManager client = new SecretsManager(SecretsManager.DEFAULT_SERVICE_NAME,
        new IamAuthenticator.Builder().apikey(apiKey).url(iamUrl).build());
client.setServiceUrl(url);

Secret secret = client.getSecretByNameType(new GetSecretByNameTypeOptions.Builder()
        .secretType("kv").name(name).secretGroupName(group).build())
        .execute().getResult();

Map<String, Object> claves = secret.getData();
```

### El secreto en sí

Se usa un único secreto de tipo **`kv`** (clave/valor), cuyo contenido es un objeto JSON:

```json
{
  "COS_API_KEY": "...",
  "COS_SERVICE_INSTANCE_ID": "crn:v1:bluemix:public:cloud-object-storage:global:a/...:...::"
}
```

En `local` el secreto lleva además `MINIO_ACCESS_KEY` y `MINIO_SECRET_KEY`, que son las
credenciales que el servicio usa realmente en ese perfil (§5).

Las claves se llaman **igual que las variables de entorno que ya usaban los YAML**. Eso no es
casualidad: es lo que hace que la integración no toque ni una línea de configuración
existente (§3).

`kv` y no `service_credentials` (que es lo que usa el proyecto de referencia
`ap6616-cos-documents-ms-app-repo`) porque aquí se guardan credenciales de **dos servicios
distintos** —COS y Redis—, no las de una única instancia enlazada. `service_credentials` lo
genera IBM al enlazar un servicio, su estructura la fija IBM y hay que navegarla a mano
(`LinkedTreeMap` anidados); `kv` es un objeto plano que uno controla.

Esa decisión se tomó antes de saber que el service credential es el mecanismo estándar de
DevOps. El repaso completo de los mecanismos disponibles, lo que implicaría cambiar y lo que
hace falta de todas formas está en §10.

### Solo lo secreto

En Secrets Manager va **lo que es secreto**, no la configuración. El endpoint del COS, el
nombre del bucket, el host de Redis o el TTL de la caché siguen siendo variables de entorno
normales: no comprometen nada, y meterlos ahí solo añadiría un punto de fallo al arranque.

## 3. Dónde encaja en la aplicación

El punto de integración es **`SecretsEnvironmentPostProcessor`**, un `EnvironmentPostProcessor`
de Spring Boot que corre al arrancar, antes de que exista el contexto:

```
arranque
   |
   v  ConfigDataEnvironmentPostProcessor carga los application-<perfil>.yaml
   |
   v  SecretsEnvironmentPostProcessor  (order = LOWEST_PRECEDENCE, para ir después)
   |     lee secrets.* del Environment
   |     si secrets.enabled=false -> no hace nada
   v
IbmSecretsManagerSource.fetch()  ->  Map<String,Object>
   |
   v  addLast(new MapPropertySource("ibm-secrets-manager", claves))
   |
   v
${COS_API_KEY} de parameters/develop/storage.yaml resuelve solo
```

La consecuencia es la que se buscaba: **ni `StorageProperties`, ni `CosConfig`, ni un solo
YAML saben que Secrets Manager existe.** Se añadió la integración sin tocar un `${...}`.

El proyecto de referencia lo resuelve al revés: un bean tipado (`GetCredentialsCommand` +
`GetCredentialsRequestFactory`) que cada consumidor inyecta. Es más explícito y más fácil de
seguir en un stack trace, pero obliga a tocar código por cada secreto nuevo, y aquí se quería
exactamente lo contrario.

Dos decisiones que conviene tener presentes:

- **`addLast()`, no `addFirst()`**: una variable de entorno real **gana** al secreto. Es una
  vía de escape para sobreescribir un valor puntual en un despliegue sin editar el secreto.
- **Se lee una sola vez, al arrancar.** No hay refresco. Si las credenciales rotan, se
  reinicia el pod. Releer en caliente obligaría a invalidar y reconstruir el cliente del COS,
  y no hay caso de uso que lo justifique.

### Si falla, no arranca

Si `secrets.enabled=true` y el secreto no se puede leer, se lanza `IllegalStateException` y
**la aplicación no levanta**:

```
java.lang.IllegalStateException: No se pudo leer el secreto 'contentms-secrets'
(grupo 'default') de Secrets Manager en http://localhost:8090
```

Es lo contrario de lo que hace la caché, que degrada a *miss* y sigue sirviendo. La diferencia
es que detrás de la caché hay un almacén durable, mientras que unas credenciales ausentes solo
pueden acabar en un 500 en la primera petición. Mismo criterio que
`CosConfig.requireConfigured`: un fallo de despliegue debe verse al desplegar.

Y los **valores nunca se loguean**. La única traza es de claves:

```
Secrets Manager: 2 claves cargadas del secreto 'contentms-secrets' (grupo 'default')
[COS_API_KEY, COS_SERVICE_INSTANCE_ID]
```

## 4. El huevo y la gallina: `IBM_CLOUD_API_KEY`

Para leer el secreto hace falta autenticarse, y **esa credencial no puede estar dentro de
Secrets Manager**: es la llave con la que se abre. Así que `IBM_CLOUD_API_KEY` sigue siendo una
variable de entorno plana, y es inevitable — con este mecanismo y con cualquier gestor de
secretos.

Lo que sí cambia es el perfil de riesgo. Se pasa de N credenciales de servicio expuestas a
**una sola**, que además:

- puede ser una service ID con permiso de *SecretsReader* **solo sobre el grupo de secretos de
  este servicio**, y nada más;
- deja rastro en el log de actividad de Secrets Manager cada vez que se usa;
- se rota en un sitio, no en tantos sitios como servicios.

> **Hubo un segundo modo y se retiró.** `secrets.auth-mode=container` usaba el token que Code
> Engine monta en el pod, canjeado contra un trusted profile, y con él **no quedaba ninguna
> credencial en el despliegue**. Se quitó porque nunca llegó a usarse y mantenerlo costaba tres
> propiedades, un authenticator y un buen trozo de este documento. Si algún día se quiere
> recuperar, el cambio se contiene en `SecretsManagerClients` — el mecanismo sigue explicado en
> [credenciales-ibm-cloud.md](credenciales-ibm-cloud.md) §4.7.

## 5. Emulación en local

En local **no se cambia de adaptador, se cambia de destino**. `deploy/docker-compose.yaml`
levanta un WireMock en el puerto 8090 que responde tanto al endpoint de token de IAM como a la
API v2 de Secrets Manager:

```
local:        IbmSecretsManagerSource -> http://localhost:8090   (WireMock)
develop/prod: IbmSecretsManagerSource -> https://<id>.us-south.secrets-manager.appdomain.cloud
```

Así, en local se recorre el **mismo camino de código** que en Code Engine: el mismo SDK, la
misma negociación de token, la misma llamada HTTP, el mismo parseo y el mismo registro del
`PropertySource`. Si el parseo está mal, se ve aquí y no en el primer despliegue. Es el mismo
criterio con el que este repo usa MinIO en vez de COS (README §4).

**Pasar a producción son dos variables de entorno**, `SECRETS_URL` y `SECRETS_IAM_URL`, más la
credencial del modo. Cero código.

### Los dos sitios desde los que se levanta

| | Comando | Ficheros del stub |
|---|---|---|
| Local (`deploy/`) | `podman-compose -f deploy/docker-compose.yaml up -d` | `deploy/secrets-manager-stub/` |
| DevX (`infra/`) | `cd infra && ./up.sh ibm-secret-manager` | `infra/conf/secrets-manager-stub/` |

Los dos publican el **8090** y sirven el mismo secreto, con **una diferencia que importa**: las
credenciales de MinIO. `deploy/` usa `minioadmin`/`minioadmin` e `infra/` usa
`admin`/`adminadmin`, y como el servicio las usa de verdad en el perfil `local`, cada entorno
tiene su propia copia del `secret-kv.json`. En `infra/` no es estático: lo genera `up.sh` desde
`secret-kv.json.template` con las credenciales de ese stack, y aborta si la sustitución no
ocurre (ver [`infra/README.md`](infra/README.md)).

### Dónde se setean los secretos en local

En `deploy/secrets-manager-stub/mappings/secret-kv.json`, objeto `data`. Es el equivalente
local del secreto `kv`: el mismo JSON que en la consola de IBM, en otro sitio.

```jsonc
"data": {
  "MINIO_ACCESS_KEY": "minioadmin",   // <- las usa de verdad el servicio
  "MINIO_SECRET_KEY": "minioadmin",
  "COS_API_KEY": "stub-cos-api-key",  // <- solo para que la forma sea la misma que fuera
  "COS_SERVICE_INSTANCE_ID": "..."
}
```

Se edita, se reinicia el stub (`podman restart contentms-secrets-stub`) y se reinicia el
servicio, que lee el secreto una sola vez al arrancar.

En `local` el servicio firma **HMAC contra MinIO**, no IAM contra el COS, así que las
credenciales que consume de verdad son `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` — y salen del
secreto, igual que en `develop`/`production` salen del secreto las del COS. `parameters/local/
storage.yaml` las lee como `${MINIO_ACCESS_KEY:minioadmin}`; el default está solo para que
`SECRETS_ENABLED=false` siga arrancando. Los valores `COS_*` no los usa nadie en local: están
para que la forma del secreto sea idéntica en todos los entornos.

**La prueba de que el mecanismo funciona** es cambiar un valor a algo incorrecto:

```bash
# MINIO_SECRET_KEY: "clave-equivocada"  en secret-kv.json
podman restart contentms-secrets-stub
# subir un archivo ->
HTTP 503  {"errorDetail":{"code":"STORAGE_UNAVAILABLE", ...}}
```

Con el valor bueno vuelve a dar 201. No cambió nada más: ni una variable de entorno, ni una
línea de código.

Comprobar que el stub se está usando:

```bash
podman logs contentms-secrets-stub
# 10.89.2.4 - POST /identity/token
# 10.89.2.4 - GET /api/v2/secret_groups/default/secret_types/kv/secrets/contentms-secrets
```

### Trabajar sin el stub

```bash
SECRETS_ENABLED=false ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

El post-processor no hace nada y los secretos vuelven a salir de variables de entorno: el
comportamiento exacto de antes de esta funcionalidad. Mismo patrón que `cache.enabled`.

## 6. Variables

Del acceso a Secrets Manager:

| Variable | Default | Para qué |
|---|---|---|
| `SECRETS_ENABLED` | `true` | A `false` desactiva Secrets Manager por completo |
| `SECRETS_URL` | `http://localhost:8090` en `local`; **sin default** en `develop`/`production` | Endpoint de la instancia |
| `SECRETS_IAM_URL` | `http://localhost:8090` en `local`; `https://iam.cloud.ibm.com` en el resto | Emisor del token. Admite la forma base y la completa (`.../identity/token`): el SDK normaliza las dos |
| `SECRETS_NAME` | `contentms-secrets` | Nombre del secreto |
| `SECRETS_GROUP` | `default` | Grupo que lo contiene |

Y las del **service credential de Redis**, que es un secreto aparte (§10):

| Variable | Default | Para qué |
|---|---|---|
| `SECRETS_REDIS_NAME` | `contentms-redis-credentials` | Nombre del secreto |
| `SECRETS_REDIS_GROUP` | vacío = el mismo que `SECRETS_GROUP` | Grupo que lo contiene. Ver abajo |

> **`SECRETS_GROUP` y `SECRETS_REDIS_GROUP` no son dos formas de decir lo mismo.** Son
> **dos secretos distintos**, y cada uno vive en un grupo: `SECRETS_NAME`/`SECRETS_GROUP`
> localizan el `kv` con las credenciales del COS, y `SECRETS_REDIS_NAME`/`SECRETS_REDIS_GROUP`
> el `service_credentials` con la conexión a Redis. Son dos porque un service credential
> pertenece a **una** instancia enlazada y no pueden compartir secreto.
>
> **En la práctica se configura solo `SECRETS_GROUP`**: `SECRETS_REDIS_GROUP` viene vacía a
> propósito y entonces hereda el grupo del secreto principal, porque lo normal es que los
> dos vivan juntos y repetirlo solo daría margen a que se desincronizaran. Se pone
> únicamente si DevOps deja el secreto de Redis en otro grupo — y en ese caso, ojo: la
> service ID necesita `SecretsReader` sobre **los dos** grupos.

De ese secreto salen el host, el puerto, el usuario de ACL, la password, la base de datos y **la CA del TLS**, ya como propiedades `spring.data.redis.*` y `spring.ssl.bundle.pem.*`. **No lleva interruptor propio: lo manda `cache.enabled`.** Sin caché no hay conexión que configurar, y con caché encendida el secreto es obligatorio — si no se puede leer, la aplicación no arranca. Los `cache.yaml` ya no declaran `spring.data.redis.*`, así que este secreto es su única fuente.

Y la credencial del arranque:

| Variable | Default | Para qué |
|---|---|---|
| `IBM_CLOUD_API_KEY` | valor falso en `local`; **sin default** en `develop`/`production` | La API key con la que se lee el secreto (§4). Obligatoria |

Configuración en `src/main/resources/parameters/<perfil>/secrets.yaml`.

`IBM_CLOUD_API_KEY` **no lleva default** en `develop`/`production`: falta ella, no arranca, y
se ve al desplegar. Es seguro declararla así porque `SecretsEnvironmentPostProcessor` enlaza
`secrets.enabled` aparte y antes que el resto, de modo que con `SECRETS_ENABLED=false` ese
placeholder no llega a resolverse nunca.

## 7. Despliegue en producción

Cuatro pasos: el secreto, la identidad que lo lee, las variables de la app, y quitar lo que
sobra. Los dos primeros se hacen una vez.

### 7.1 De dónde sale cada variable del bootstrap

Los valores de `secrets.*` son el **bootstrap**: la configuración necesaria para *llegar* al
secreto, así que por definición no pueden salir de él. Pero casi todos **no son secretos** —una
URL, un nombre, un grupo, un flag— y van como variables de entorno normales. La única que lo es
de verdad es la API key (§4).

| Variable | De dónde sale | ¿Hay que ponerla? |
|---|---|---|
| `SECRETS_ENABLED` | — | No, el default `true` vale |
| `SECRETS_URL` | El endpoint de tu instancia (§7.2) | **Sí** |
| `SECRETS_IAM_URL` | Fijo de IBM Cloud | No, salvo endpoint privado (§7.2) |
| `SECRETS_NAME` | El nombre que le diste al secreto | No, si lo llamas `contentms-secrets` |
| `SECRETS_GROUP` | El grupo donde lo creaste | No, si usas `default` |
| `IBM_CLOUD_API_KEY` | API key de una service ID que creas tú (§7.3) | **Sí**, como secreto de Code Engine |

En la práctica, en producción **se ponen dos cosas**: `SECRETS_URL` e `IBM_CLOUD_API_KEY`.

### 7.2 El endpoint: `SECRETS_URL`

El formato es `https://{instance_id}.{region}.secrets-manager.appdomain.cloud`, donde
`instance_id` es el **GUID** de la instancia, no su nombre:

```bash
ibmcloud resource service-instance contentms-secrets-manager --output json | jq -r '.[0].guid'
# -> 0f4c764e-dc3d-44d1-bd60-a2f7cd91e0c0

# SECRETS_URL=https://0f4c764e-dc3d-44d1-bd60-a2f7cd91e0c0.us-south.secrets-manager.appdomain.cloud
```

En la consola está en la instancia, pestaña **Endpoints**.

Desde Code Engine conviene el **endpoint privado**, que no sale a internet:

```
https://0f4c764e-....private.us-south.secrets-manager.appdomain.cloud
```

Requiere tener habilitados los service endpoints y VRF en la cuenta. Si se usa,
`SECRETS_IAM_URL` pasa a ser `https://private.iam.cloud.ibm.com`; si no, no se toca ninguna de
las dos.

### 7.3 La identidad: la service ID que lee el secreto

**No uses tu API key personal ni la de la cuenta.** Crea una service ID con permiso de solo
lectura sobre Secrets Manager:

```bash
# 1. la identidad
SERVICE_ID=$(ibmcloud iam service-id-create contentms-sm-reader \
  --description "Lectura del secreto de ContentMS" --output json | jq -r '.id')

# 2. el permiso: SecretsReader, solo sobre esa instancia
ibmcloud iam service-policy-create $SERVICE_ID \
  --roles SecretsReader --service-name secrets-manager \
  --service-instance <GUID de la instancia>

# 3. la API key
ibmcloud iam service-api-key-create contentms-key $SERVICE_ID --output json | jq -r '.apikey'
```

`SecretsReader` deja **leer el valor** de los secretos, y nada más: ni crear, ni rotar, ni
borrar. Para acotarla además a un único grupo de secretos, los flags `--resource-type` /
`--resource` de `ibmcloud iam service-policy-create --help`: varían entre versiones del CLI,
conviene mirarlos ahí.

Esa API key va como **secreto de Code Engine**, no como variable en claro:

```bash
ibmcloud ce secret create --name contentms-sm --from-literal IBM_CLOUD_API_KEY=<la key>
ibmcloud ce app update --name content-ms --env-from-secret contentms-sm
```

### 7.4 Crear el secreto

Con la CLI (`ibmcloud plugin install secrets-manager`):

```bash
ibmcloud secrets-manager secret-create \
  --secret-prototype '{
    "secret_type": "kv",
    "name": "contentms-secrets",
    "secret_group_id": "default",
    "description": "Credenciales de ContentMS",
    "data": {
      "COS_API_KEY": "...",
      "COS_SERVICE_INSTANCE_ID": "crn:v1:bluemix:public:cloud-object-storage:global:a/...:...::"
    }
  }'

# comprobar (devuelve los valores: cuidado con el historial de la shell)
ibmcloud secrets-manager secret-by-name \
  --secret-type kv --name contentms-secrets --secret-group-name default
```

Desde la consola: **Secrets Manager > Secrets > Add > Key-value**, y pegar el JSON.

### 7.5 Las variables de la app

En modo `apikey`, la API key va como **secreto de Code Engine**, no como `--env`, para que no
quede en texto plano en la configuración de la app:

```bash
ibmcloud ce secret create --name contentms-bootstrap \
  --from-literal IBM_CLOUD_API_KEY=<la apikey de 7.3>

ibmcloud ce app update --name content-ms \
  --env SECRETS_URL=https://<guid>.us-south.secrets-manager.appdomain.cloud \
  --env-from-secret contentms-bootstrap
```

### 7.6 Quitar lo que sobra

Es lo que más fácil se olvida:

```bash
ibmcloud ce app update --name content-ms \
  --unset-env COS_API_KEY --unset-env COS_SERVICE_INSTANCE_ID
```

Mientras esas variables sigan puestas **ganan al secreto** (§3), Secrets Manager quedaría
leyéndose para nada y la migración no estaría hecha de verdad.

### 7.7 Verificar

En el arranque tiene que aparecer, sin excepción previa:

```
Secrets Manager: 2 claves cargadas del secreto 'contentms-secrets' (grupo 'default')
[COS_API_KEY, COS_SERVICE_INSTANCE_ID]
```

Si la credencial no tiene permiso, esa línea no aparece: **la app no arranca** y el log lleva el
`IllegalStateException: No se pudo leer el secreto ...`, que nombra el secreto, el grupo y la
instancia, con el 403 del SDK debajo. Es deliberado (§3): un fallo de credenciales tiene que
verse al desplegar, no en la primera petición.

## 8. Añadir un secreto nuevo

1. Añade la clave al JSON del secreto, con el mismo nombre que la variable de entorno.
2. Úsala en el YAML del perfil como `${MI_VARIABLE}`, igual que cualquier otra.

No hay paso 3: no se toca código Java.

## 9. Puntos de extensión

- **Otro tipo de secreto** (`service_credentials`, `arbitrary`): `IbmSecretsManagerSource` solo
  lee `kv`, y lo dice explícitamente si le llega otro tipo. Para soportar más, el cambio se
  contiene en esa clase — `SecretsSource` devuelve un `Map` y a nadie más le importa de dónde
  sale. Los tipos que existen y cuándo conviene cada uno, en §10.
- **Más de un secreto**: hoy se lee uno. Leer varios y fusionarlos es un bucle en el
  post-processor. Haría falta si se pasa a `service_credentials`, que es un secreto por
  instancia enlazada (§10.1).
- **Rotación en caliente**: fuera de alcance, ver §3.

## 10. Los mecanismos para obtener una credencial de conexión

Esta sección es el contexto de la decisión de §2, escrita después de que DevOps confirmara que
el mecanismo estándar de la organización es el **service credential**, y que el proyecto de
referencia `ap6616-cos-documents-ms-app-repo` lo usa así para MongoDB (`GetCredentialsCommand`
+ `GetCredentialsRequestFactory`).

No es una lista de alternativas equivalentes: cada una entrega **cosas distintas**, rota de
forma distinta y se ensaya de forma distinta. Lo que sigue es cómo funciona cada una y qué hay
que tener en cuenta al elegir.

> Esta sección asume el vocabulario y da la conclusión. Si lo que buscas es entender los
> mecanismos desde cero, con diagramas y ejemplos, el documento es
> **[credenciales-ibm-cloud.md](credenciales-ibm-cloud.md)**.

### 10.1 Los mecanismos

**1. Variable de entorno plana en el despliegue.** Alguien copia el valor a la configuración de
la app. Es lo que hacía este servicio antes de §1. Cero infraestructura; sin rotación, sin
auditoría, visible para cualquiera con permiso de lectura sobre la app. Sigue siendo el
mecanismo del **bootstrap**: de algo tiene que arrancar la cadena (§4).

**2. Secret de Code Engine, como variable o montado como volumen.** El valor vive en un objeto
`secret` de la plataforma, no en el env de la app. Mejora el control de acceso, pero la rotación
sigue siendo manual y no hay log de accesos. Montado como volumen permite releer sin reiniciar;
inyectado como variable, no.

**3. Service binding de Code Engine.** Se enlaza la instancia de servicio a la app y la
plataforma inyecta las credenciales como variables de entorno con prefijo, más un JSON
agregado. No hay SDK, ni llamada HTTP, ni credencial de bootstrap. En cambio la forma la pone
IBM y varía por servicio, rotar implica re-bindear y reiniciar, y **no se puede emular en
local**: se prueba desplegando.

**4. Secrets Manager, tipo `kv` o `arbitrary`.** *Lo que usa este servicio.* El contenido lo
escribe uno: `arbitrary` es un `payload` de texto, `kv` un objeto JSON plano. Se lee con
`getSecretByNameType` y `secret.getData()`. Como la forma la controlamos, las claves se llaman
igual que las variables de los YAML y no hay ni una línea de mapeo (§2, §3). El precio es que
**el contenido se copia a mano**: si IBM regenera la credencial del servicio, hay que
actualizar el `kv`. No hay rotación automática, porque IBM no sabe qué hay dentro.

**5. Secrets Manager, tipo `service_credentials`.** *Lo que usa DevOps.* Se le indica la
instancia y el rol, y Secrets Manager **crea él mismo la service credential** contra ese
servicio y guarda la respuesta en un objeto `credentials` anidado. Se lee con el mismo
`getSecretByNameType`, pero el resultado se saca con `getCredentials()` y hay que navegar
`connection.<servicio>.…` a mano. Es el único que **puede rotar solo** —Secrets Manager
regenera la credencial en el servicio y guarda una versión nueva—, y de ahí que sea el
estándar. A cambio: la forma la fija IBM, el mapeo es código, y **un secreto es una instancia
enlazada**, así que COS y Redis serían dos secretos (§9, "más de un secreto").

**6. Secrets Manager, tipo `iam_credentials`.** Secrets Manager crea API keys de IAM dinámicas
contra una service ID, opcionalmente con TTL: la credencial nace al pedirla y muere al expirar.
Es el más fuerte para lo que se autentica con IAM —el COS es exactamente ese caso—, y **no
sirve para Redis**, que usa usuario/password de ACL, no IAM. Si la credencial es efímera, la
app tiene que poder releer, y aquí se lee una sola vez al arrancar (§3).

**7. Identidad de plataforma (trusted profile / compute resource token).** No es un almacén: es
la respuesta al huevo y la gallina. Code Engine monta un token en el pod, el SDK lo canjea en
IAM contra un trusted profile y **no queda ninguna credencial en el despliegue**. Estuvo
implementado aquí y se retiró por falta de uso (§4). Elimina la credencial de bootstrap, y en
teoría podría eliminar también la del COS, que habla IAM — pero **nunca la de Redis**.

### 10.2 Lo transversal

**Una credencial de conexión no es solo usuario y password.** Es lo que más se subestima. El
service credential real de Redis (`service-credentials/serviceCredentialsRedis.json`) trae:

```
scheme:                  rediss                     <- TLS obligatorio
hostname:                42e60302-….private.databases.appdomain.cloud
port:                    31273
authentication.username  ibm_cloud_e7d765a7_…       <- usuario de ACL, no solo password
database:                0
certificate_authority:   self_signed                <- la JVM no confia en esa CA
certificate_base64:      LS0tLS1CRUdJTi…            <- hay que construir un truststore
```

Son cinco campos más allá de la password, y el certificado es el peor: es una CA que la JVM no
conoce. Hoy `parameters/{develop,production}/cache.yaml` solo tiene `host`, `port` y `password`
—no existe `spring.data.redis.username` ni `ssl` en ningún sitio del repo, y la
`RedisConnectionFactory` sale entera de la autoconfiguración—, así que **con la configuración
actual el servicio no puede conectarse a una instancia de Databases for Redis provisionada así,
se lea el secreto como se lea**. No es un problema de *de dónde* sale la credencial, sino de
*qué campos* necesita la conexión.

**Rotación y cuándo se lee.** Se lee una sola vez, en el `EnvironmentPostProcessor`, antes de
que exista el contexto (§3). Eso encaja con `kv` y con un `service_credentials` estático —rotar
es reiniciar el pod— y **no** encaja con credenciales de TTL corto. Un `iam_credentials` con
expiración obligaría a cambiar ese modelo.

**El bootstrap siempre existe.** Para leer del gestor hace falta identidad: o una API key plana,
o el token de la plataforma. No hay tercera opción, y solo la segunda deja el despliegue sin
ninguna credencial (§4).

**Precedencia.** El secreto se registra con `addLast()`, así que una variable de entorno real
**gana** al secreto (§3). Es la vía de escape, y es la trampa: un `COS_API_KEY` olvidado en el
despliegue silencia el secreto sin decir nada.

**Fallar al arrancar frente a degradar.** Un secreto ilegible mata el arranque (§3); la caché
degrada a *miss*. Combinadas tienen un efecto que conviene tener presente: si las credenciales
de Redis llegan bien pero el TLS falla, `CacheConfig.errorHandler()` lo baja a WARN,
`management.health.redis.enabled` está en `false` en todos los perfiles, y **el servicio
responde 200 en todo con una caché que no cachea nada**. Ese fallo solo se ve con
`redis-cli KEYS`, nunca con un health check.

**Endpoint público frente a privado.** El hostname del service credential es
`…private.databases.appdomain.cloud`: eso no es un detalle del secreto, es conectividad. Solo
resuelve desde dentro, con los service endpoints habilitados. Un secreto perfecto contra un
endpoint inalcanzable falla igual. Mismo asunto que el endpoint privado de Secrets Manager
(§7.2).

**Cómo se ensaya en local.** Cuanto más dependa el mecanismo de la plataforma, menos se puede
probar antes de desplegar. `kv` y `service_credentials` se emulan igual de bien con el WireMock
que ya está —mismo SDK, mismo camino de código, otra URL (§5)—; un service binding no.

### 10.3 Qué implicaría cambiar, y qué hace falta de todas formas

Son dos decisiones separables, y no dependen una de la otra:

- **Redis necesita `username`, `ssl.enabled`, un truststore construido en arranque desde el
  `certificate_base64`, y acceso al endpoint privado.** Hace falta con `kv`, con
  `service_credentials` y con service binding. Es lo que hoy bloquea la caché en `develop` y
  `production`, y es lo urgente.
- **Para el COS sí hay elección real, y no hay que tomarla todavía.** `service_credentials` si
  DevOps lo va a entregar así; `iam_credentials` si se quieren API keys rotadas por IBM; o
  seguir con `kv` y no tocar nada. Los tres se leen con el mismo `getSecretByNameType` que ya
  usa `IbmSecretsManagerSource`: cambia el `secretType` y el parseo.

Si se migra a `service_credentials`, el trabajo está en cuatro sitios: el `secretType` y el
parseo en `IbmSecretsManagerSource` (la aserción de tipo y el `return secret.getData()`), un
bucle en `SecretsEnvironmentPostProcessor` para leer dos secretos, la forma de `secrets.*` en
los YAML y su tabla de variables (§6), y el segmento `secret_types/kv`, que está hardcoded en
cinco sitios del stub: `deploy/secrets-manager-stub/mappings/secret-kv.json`,
`infra/conf/secrets-manager-stub/mappings/secret-kv.json`, su `.template`, el `SECRET_PATH` de
`infra/reload-secret.sh` y las constantes de fichero de `infra/up.sh`.

Un camino intermedio, si se decide seguir: mantener `kv` como default y añadir el tipo como
propiedad conmutable. El punto de extensión ya está contenido en `IbmSecretsManagerSource`
(§9).

### 10.4 Preguntas abiertas para DevOps

1. ¿Se entregarán COS y Redis como **dos** service credentials, o el COS se queda como está?
2. ¿Se accede al endpoint `…private.databases.appdomain.cloud` desde Code Engine, o hay uno
   público? ¿Están habilitados los service endpoints y VRF en la cuenta?
3. ¿Se confirma que el TLS es obligatorio y que el `certificate_base64` del secreto es la CA
   que hay que confiar? ¿O IBM publica esa CA en algún sitio estable?
4. ¿Quién regenera el service credential, con qué frecuencia, y avisa? Al leerse una sola vez
   al arrancar, una rotación exige reiniciar el pod (§3).
