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
  "COS_SERVICE_INSTANCE_ID": "crn:v1:bluemix:public:cloud-object-storage:global:a/...:...::",
  "REDIS_PASSWORD": "..."
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
Secrets Manager: 3 claves cargadas del secreto 'contentms-secrets' (grupo 'default')
[COS_API_KEY, COS_SERVICE_INSTANCE_ID, REDIS_PASSWORD]
```

## 4. El huevo y la gallina: `IBM_CLOUD_API_KEY`

Para leer el secreto hace falta autenticarse. Hay **dos formas**, y de cuál se use depende que
quede o no una credencial suelta en el despliegue. Se elige con `secrets.auth-mode` (§7.3).

### Modo `apikey` — el default

Se autentica con una API key de IBM Cloud. **Esa credencial no puede estar dentro de Secrets
Manager**: es la llave con la que se abre. Sigue siendo una variable de entorno plana, y es
inevitable — con este modo y con cualquier gestor de secretos.

Lo que sí cambia es el perfil de riesgo. Se pasa de N credenciales de servicio expuestas a
**una sola**, que además:

- puede ser una service ID con permiso de *SecretsReader* **solo sobre el grupo de secretos de
  este servicio**, y nada más;
- deja rastro en el log de actividad de Secrets Manager cada vez que se usa;
- se rota en un sitio, no en tantos sitios como servicios.

### Modo `container` — el huevo y la gallina desaparece

Code Engine monta un token en el sistema de ficheros del pod; el SDK lo lee y lo canjea en IAM
contra un **trusted profile**. **No queda ninguna credencial en el despliegue**: la identidad se
la da la plataforma al pod, igual que IRSA en EKS o las workload identities de GCP.

```
/var/run/secrets/codeengine.cloud.ibm.com/compute-resource-token/token
                    |
                    v  el SDK lo lee del disco
   POST https://iam.cloud.ibm.com/identity/token
        grant_type = urn:ibm:params:oauth:grant-type:cr-token
        cr_token   = <el contenido del fichero>
        profile_name = <trusted profile>
                    |
                    v
             token IAM normal  ->  y a partir de aquí, todo igual
```

El precio es una dependencia de la plataforma: hace falta que alguien cree el trusted profile y
lo enlace a la app. Por eso el default sigue siendo `apikey` y el modo se conmuta con una
variable de entorno (§7.3), sin tocar código ni imagen.

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

El fichero `cr-token` para ensayar el modo `container` está en los dos sitios; usa el del
entorno que tengas levantado.

### Dónde se setean los secretos en local

En `deploy/secrets-manager-stub/mappings/secret-kv.json`, objeto `data`. Es el equivalente
local del secreto `kv`: el mismo JSON que en la consola de IBM, en otro sitio.

```jsonc
"data": {
  "MINIO_ACCESS_KEY": "minioadmin",   // <- las usa de verdad el servicio
  "MINIO_SECRET_KEY": "minioadmin",
  "COS_API_KEY": "stub-cos-api-key",  // <- solo para que la forma sea la misma que fuera
  "COS_SERVICE_INSTANCE_ID": "...",
  "REDIS_PASSWORD": ""
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

### Ensayar el modo `container` en local

El `ContainerAuthenticator` lee el token de un fichero y lo canjea en el **mismo**
`POST /identity/token` que el stub ya sirve: solo cambia el cuerpo del formulario. Por eso basta
con apuntarle a un fichero de mentira, que viaja en el repo:

```bash
SECRETS_AUTH_MODE=container \
SECRETS_CR_TOKEN_FILE=deploy/secrets-manager-stub/cr-token \
SECRETS_IAM_PROFILE_NAME=contentms-sm-reader \
java -jar target/content-ms-1.0.0.jar --spring.profiles.active=local
```

Arranca igual que en modo `apikey`, con la misma traza de claves cargadas. Que el canje fue
realmente el del modo `container` se ve en el diario de peticiones de WireMock:

```bash
curl -s 'http://localhost:8090/__admin/requests?limit=2' | grep grant_type
# grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Acr-token&cr_token=...&profile_name=...
```

frente al del modo por defecto:

```
# grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Aapikey&apikey=...
```

Así el modo deja de ser un salto de fe: se comprueba que el SDK lee el fichero, construye el
canje correcto y sigue adelante, **antes** de pedirle nada a DevOps.

Lo que esto **no** demuestra es que Code Engine monte de verdad el token en su ruta ni que el
trusted profile tenga permiso. Eso solo se ve en el primer despliegue.

### Trabajar sin el stub

```bash
SECRETS_ENABLED=false ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

El post-processor no hace nada y los secretos vuelven a salir de variables de entorno: el
comportamiento exacto de antes de esta funcionalidad. Mismo patrón que `cache.enabled`.

## 6. Variables

Comunes a los dos modos:

| Variable | Default | Para qué |
|---|---|---|
| `SECRETS_ENABLED` | `true` | A `false` desactiva Secrets Manager por completo |
| `SECRETS_AUTH_MODE` | `apikey` | `apikey` o `container` (§4, §7.3) |
| `SECRETS_URL` | `http://localhost:8090` en `local`; **sin default** en `develop`/`production` | Endpoint de la instancia |
| `SECRETS_IAM_URL` | `http://localhost:8090` en `local`; `https://iam.cloud.ibm.com` en el resto | Emisor del token. Admite la forma base y la completa (`.../identity/token`): el SDK normaliza las dos |
| `SECRETS_NAME` | `contentms-secrets` | Nombre del secreto |
| `SECRETS_GROUP` | `default` | Grupo que lo contiene |

Solo en modo `apikey`:

| Variable | Default | Para qué |
|---|---|---|
| `IBM_CLOUD_API_KEY` | valor falso en `local`; vacío fuera | La API key con la que se lee el secreto (§4). **En modo `container` no debe existir** |

Solo en modo `container`:

| Variable | Default | Para qué |
|---|---|---|
| `SECRETS_IAM_PROFILE_NAME` | vacío | Nombre del trusted profile. Obligatorio, salvo que se use el id |
| `SECRETS_IAM_PROFILE_ID` | vacío | Alternativa al nombre |
| `SECRETS_CR_TOKEN_FILE` | vacío | Fichero del que leer el token del pod. Vacío deja que el SDK pruebe sus tres rutas por defecto, una de las cuales es la de Code Engine. Solo hace falta fijarlo para ensayar en local (§5) o en un runtime que lo monte en otro sitio |

Configuración en `src/main/resources/parameters/<perfil>/secrets.yaml`.

`IBM_CLOUD_API_KEY` lleva default vacío en `develop`/`production` a propósito: sin él, el
arranque en modo `container` reventaría por un placeholder sin resolver antes de poder decir
nada útil. El fallo rápido no se pierde, se mueve — si falta estando en modo `apikey`, la
validación de `IbmSecretsManagerSource` lo dice nombrando el modo.

## 7. Despliegue en producción

Cuatro pasos: el secreto, la identidad que lo lee, las variables de la app, y quitar lo que
sobra. Los dos primeros se hacen una vez.

### 7.1 De dónde sale cada variable del bootstrap

Los valores de `secrets.*` son el **bootstrap**: la configuración necesaria para *llegar* al
secreto, así que por definición no pueden salir de él. Pero casi todos **no son secretos** —una
URL, un nombre, un grupo, un flag— y van como variables de entorno normales. La única que lo es
resulta ser, además, la que desaparece en modo `container` (§4).

| Variable | De dónde sale | ¿Hay que ponerla? |
|---|---|---|
| `SECRETS_ENABLED` | — | No, el default `true` vale |
| `SECRETS_AUTH_MODE` | Lo decides tú (§7.3) | No, el default `apikey` es lo que ya funciona |
| `SECRETS_URL` | El endpoint de tu instancia (§7.2) | **Sí** |
| `SECRETS_IAM_URL` | Fijo de IBM Cloud | No, salvo endpoint privado (§7.2) |
| `SECRETS_NAME` | El nombre que le diste al secreto | No, si lo llamas `contentms-secrets` |
| `SECRETS_GROUP` | El grupo donde lo creaste | No, si usas `default` |
| `IBM_CLOUD_API_KEY` | API key de una service ID que creas tú (§7.3) | **Sí en modo `apikey`**, como secreto de Code Engine. En modo `container` **no debe existir** |
| `SECRETS_IAM_PROFILE_NAME` | El trusted profile que enlaza DevOps (§7.3) | **Sí en modo `container`** |
| `SECRETS_CR_TOKEN_FILE` | — | No: el SDK ya conoce la ruta de Code Engine |

En la práctica, en producción **se ponen dos cosas**: `SECRETS_URL` y la credencial que
corresponda al modo.

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

### 7.3 La identidad: elegir modo

Aquí es donde se decide si queda o no una credencial en el despliegue (§4). El default es
`apikey`, así que **si no se hace nada, el despliegue funciona y no hay que pedirle nada nuevo a
DevOps**.

| Modo | Credencial en el despliegue | Lo que hay que pedirle a DevOps |
|---|---|---|
| `apikey` (default) | `IBM_CLOUD_API_KEY` | Una service ID con `SecretsReader` y su API key |
| `container` | Ninguna | Un trusted profile con `SecretsReader`, enlazado a la app de Code Engine |

#### Modo `apikey`

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

#### Modo `container`

En vez de una service ID, un **trusted profile** al que se le da el mismo rol y que se enlaza a
la app de Code Engine como identidad de recurso de cómputo. La app no lleva credencial: recibe
del pod un token que el SDK canjea contra ese perfil.

Esta parte es trabajo de DevOps en IBM Cloud (crear el perfil, darle `SecretsReader` sobre la
instancia y enlazarlo a la app). Del lado del servicio no hay nada que hacer más que decirle el
nombre del perfil.

#### Conmutar y volver atrás

Es una variable de entorno y un reinicio. Ni imagen nueva, ni código, ni despliegue distinto:

```bash
# a modo container
ibmcloud ce app update --name content-ms \
  --env SECRETS_AUTH_MODE=container \
  --env SECRETS_IAM_PROFILE_NAME=contentms-sm-reader

# rollback
ibmcloud ce app update --name content-ms --env SECRETS_AUTH_MODE=apikey
```

Se puede dejar `IBM_CLOUD_API_KEY` puesta mientras se prueba `container`: en ese modo se
ignora, y volver a `apikey` es inmediato. Cuando el modo `container` esté consolidado, quítala.

También se puede probar `container` **solo en develop** y dejar production en `apikey`: son
perfiles distintos con variables distintas.

Un matiz: el secreto se lee una sola vez al arrancar, así que conmutar implica reiniciar el pod.
No es un flag en caliente.

#### Por qué no hay fallback automático

Tentaría intentar `container` y caer a `apikey` si falla. **No se hace**, por la misma razón por
la que un fallo de secreto no arranca la app en vez de degradar en silencio (§3):

- No se sabría con qué identidad corre el servicio, y el log de auditoría de Secrets Manager
  mostraría accesos de las dos sin poder explicar cuál fue cuándo.
- Un trusted profile mal configurado quedaría tapado por el fallback y la migración no se
  completaría nunca — el mismo fallo silencioso que un `COS_API_KEY` olvidado ganándole al
  secreto (§7.6).

El modo es explícito, y un modo mal configurado **no arranca**, con un mensaje que dice qué
falta:

```
IllegalStateException: Con secrets.auth-mode=container hace falta secrets.iam-profile-name
o secrets.iam-profile-id: es el trusted profile contra el que se canjea el token del pod
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
      "COS_SERVICE_INSTANCE_ID": "crn:v1:bluemix:public:cloud-object-storage:global:a/...:...::",
      "REDIS_PASSWORD": "..."
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

En modo `container` no hay ningún secreto que crear — solo variables normales:

```bash
ibmcloud ce app update --name content-ms \
  --env SECRETS_URL=https://<guid>.us-south.secrets-manager.appdomain.cloud \
  --env SECRETS_AUTH_MODE=container \
  --env SECRETS_IAM_PROFILE_NAME=contentms-sm-reader
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
Secrets Manager: 3 claves cargadas del secreto 'contentms-secrets' (grupo 'default')
[COS_API_KEY, COS_SERVICE_INSTANCE_ID, REDIS_PASSWORD]
```

Si la credencial no tiene permiso, esa línea no aparece: **la app no arranca** y el log lleva el
`IllegalStateException: No se pudo leer el secreto ...`, que además dice **con qué modo** se
intentó, y el 403 del SDK debajo. Es deliberado (§3): un fallo de credenciales tiene que verse
al desplegar, no en la primera petición.

En modo `container`, un fallo típico es que el pod no tenga el token montado; el mensaje lo
delata:

```
IllegalStateException: No se pudo leer el secreto 'contentms-secrets' ... con autenticacion 'container'
Caused by: java.lang.RuntimeException: Error reading CR token file: /var/run/secrets/...
```

lo que apunta a que el trusted profile no está enlazado a la app.

## 8. Añadir un secreto nuevo

1. Añade la clave al JSON del secreto, con el mismo nombre que la variable de entorno.
2. Úsala en el YAML del perfil como `${MI_VARIABLE}`, igual que cualquier otra.

No hay paso 3: no se toca código Java.

## 9. Puntos de extensión

- **Otro tipo de secreto** (`service_credentials`, `arbitrary`): `IbmSecretsManagerSource` solo
  lee `kv`, y lo dice explícitamente si le llega otro tipo. Para soportar más, el cambio se
  contiene en esa clase — `SecretsSource` devuelve un `Map` y a nadie más le importa de dónde
  sale.
- **Más de un secreto**: hoy se lee uno. Leer varios y fusionarlos es un bucle en el
  post-processor.
- **Rotación en caliente**: fuera de alcance, ver §3.
