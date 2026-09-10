# Probar la conexión a Redis, y qué variables poner en cada entorno

La conexión a Redis ya no sale de `spring.data.redis.*`: sale del **service credential** que
ContentMS lee de IBM Cloud Secrets Manager al arrancar. Del secreto vienen el host, el puerto, el
usuario de ACL, la password, la base de datos y **la CA del TLS**.

Este documento es el paso a paso para probarlo en local y la lista de variables por entorno. El
porqué del mecanismo está en [`credenciales-ibm-cloud.md`](credenciales-ibm-cloud.md) y
[`secret-manager.md`](secret-manager.md).

---

## 1. Antes de nada: un 200 no prueba nada

Es lo más importante de esta página. Si el TLS falla, **el servicio sigue respondiendo 200 en
todas las peticiones** y la caché simplemente no guarda nada:

- `CacheConfig.errorHandler()` degrada cualquier fallo de Redis a un WARN y sirve desde el COS.
- `management.health.redis.enabled` está en `false` en los tres perfiles, así que
  `/actuator/health` tampoco se entera.

Las dos únicas pruebas que valen son:

1. **Ver la clave en Redis** (`KEYS 'contentms:*'`).
2. **Ver la conexión en el puerto TLS y con el usuario del secreto** (`CLIENT LIST` →
   `laddr=...:6380 user=contentms`).

Cualquier otra cosa —que arranque, que devuelva 200, que el health esté verde— es compatible con
una caché que no funciona.

---

## 2. Paso a paso en local

Requisitos: **JDK 21**, **Podman o Docker** y **`openssl`** (lo usa `up.sh` para generar los
certificados; si no está, aborta diciéndolo).

### 2.1 Levantar el stack

```bash
cd infra
./up.sh redis minio ibm-secret-manager
```

Eso hace tres cosas nuevas respecto a antes:

- genera una **CA autofirmada** y el certificado del servidor en `infra/conf/redis-tls/`
  (con SAN para `localhost`, `redis` y `127.0.0.1`; no se commitean);
- arranca Redis escuchando en **dos puertos**: `6379` en claro y `6380` con TLS;
- renderiza **dos** secretos en `conf/secrets-manager-stub/mappings/`: el `kv` de siempre y
  `secret-redis.json`, el service credential, con la CA dentro.

> Redis mantiene el puerto en claro a propósito: Redis Commander (el 8081) habla en claro, y en
> DevX es la única forma de mirar la caché, porque allí no hay `exec` ni acceso al 6379 por TCP.
> El usuario `default` se queda sin password para eso; la aplicación usa el usuario de ACL
> `contentms`.

### 2.2 Comprobar que Redis habla TLS con esa CA

```bash
openssl s_client -connect localhost:6380 -CAfile conf/redis-tls/ca.crt </dev/null 2>/dev/null \
  | grep -E 'subject=|issuer=|Verify return code'
```

Esperado:

```
subject=CN=localhost
issuer=CN=infra-redis-ca
    Verify return code: 0 (ok)
```

### 2.3 Comprobar que el stub sirve el service credential

```bash
curl -s -o /dev/null -w 'HTTP %{http_code}\n' \
  'http://localhost:8090/api/v2/secret_groups/default/secret_types/service_credentials/secrets/contentms-redis-credentials'
```

Esperado: `HTTP 200`. (El `kv` de siempre sigue en
`.../secret_types/kv/secrets/contentms-secrets`.)

### 2.4 Compilar y arrancar

```bash
cd ..
./mvnw clean package
java -jar target/content-ms-1.0.0.jar --spring.profiles.active=local
```

En Windows: `.\mvnw.cmd clean package`.

### 2.5 Las dos líneas del log que hay que buscar

```
Secrets Manager: 4 claves cargadas del secreto 'contentms-secrets' (grupo 'default')
  [COS_API_KEY, COS_SERVICE_INSTANCE_ID, MINIO_ACCESS_KEY, MINIO_SECRET_KEY]

Secrets Manager: conexion a Redis tomada del service credential 'contentms-redis-credentials'
  (grupo 'default'); 8 propiedades [spring.data.redis.database, spring.data.redis.host,
  spring.data.redis.password, spring.data.redis.port, spring.data.redis.ssl.bundle,
  spring.data.redis.ssl.enabled, spring.data.redis.username,
  spring.ssl.bundle.pem.contentms-redis.truststore.certificate]
```

Si la segunda no aparece, el interruptor está apagado: busca
`Service credential de Redis desactivado`.

### 2.6 Subir un archivo

```bash
curl -X POST http://localhost:8080/v1/save-content \
  -H 'correlation_id: 11111111-1111-1111-1111-111111111111' \
  -H 'request_id: 22222222-2222-2222-2222-222222222222' \
  -H '_p: 12345' \
  -F 'jsonString={"fileName":"prueba.png","partnerId":"12345"};type=application/json' \
  -F 'file=@prueba.png'
```

Esperado: `{"returnCode":"201","message":"Created"}`.

> **El bucket solo admite `image/png`, `image/jpeg`, `image/gif`, `image/svg+xml` y
> `application/pdf`.** Con un `.txt` responde `422 UNSUPPORTED_CONTENT_TYPE`, que despista si no
> lo sabes. El tipo sale de la extensión del `fileName`, no de lo que declare el cliente.

### 2.7 Pedirlo dos veces y verificar la caché

```bash
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8080/v1/content-loaded?context_url=12345/prueba.png'
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8080/v1/content-loaded?context_url=12345/prueba.png'
```

Y ahora la comprobación de verdad:

```bash
podman exec infra-redis redis-cli --tls --cacert /tls/ca.crt -p 6380 \
  --user contentms --pass contentms-local KEYS 'contentms:*'
```

Esperado:

```
contentms:content::cmsContent/12345/prueba.png
```

> **En Git Bash, antepón `MSYS_NO_PATHCONV=1`.** Si no, convierte `/tls/ca.crt` en una ruta de
> Windows y `redis-cli` contesta `Invalid CA Certificate File/Directory`, que parece un problema
> de certificados y no lo es.

En DevX, donde no hay `exec`, la vía es **Redis Commander en el 8081** (`admin`/`admin`): la
clave tiene que aparecer ahí.

### 2.8 La comprobación que cierra el asunto

Que haya una clave no dice por qué puerto se guardó. Esto sí:

```bash
podman exec infra-redis redis-cli -p 6379 CLIENT LIST
```

Esperado, entre los clientes:

```
laddr=10.89.0.2:6380 ... user=contentms
```

`6380` y `contentms` son el puerto y el usuario que vienen **del secreto**. Si vieras la
aplicación en `6379` con `user=default`, estarías probando el camino viejo sin darte cuenta.

---

## 3. Pruebas negativas

Son las que dan valor al ensayo: demuestran que el certificado del secreto se usa de verdad. El
ciclo para tocar un secreto son **tres** pasos, y los tres hacen falta:

```bash
vi infra/conf/secrets-manager-stub/mappings/secret-redis.json   # 1. editar
cd infra && ./reload-secret.sh                                  # 2. que WireMock lo relea
#                                                                 3. reiniciar la aplicación
```

El tercero no es opcional: el secreto se lee **una sola vez, al arrancar**.

| Prueba | Cómo | Resultado esperado |
|---|---|---|
| **CA equivocada** | Sustituir `certificate_base64` por el de otra CA | **200 en los dos GET, `KEYS` vacío**, y en el log `SSLHandshakeException: unable to find valid certification path`. Es el fallo silencioso en directo |
| **Password de ACL equivocada** | Cambiar `authentication.password` | Igual: 200, caché vacía, y un WARN con `WRONGPASS` |
| **Secreto ausente** | Renombrar `secret-redis.json` y recargar | **La aplicación no arranca**: `No se pudo leer el service credential de Redis 'contentms-redis-credentials' ...` |
| **Sin caché** | `CACHE_ENABLED=false` | Arranca sin leer el service credential y sin decorador: el GET siempre va al COS |

Después de la primera y la segunda, para volver al estado bueno:

```bash
cd infra && ./up.sh --regen-secret redis ibm-secret-manager
```

---

## 4. Variables en local

**No hay que exportar nada.** Los defaults de `parameters/local/secrets.yaml` ya apuntan al stub
del 8090 y traen el service credential encendido. Esto es lo que se *puede* tocar:

| Variable | Default en `local` | Para qué |
|---|---|---|
| `SECRETS_REDIS_NAME` | `contentms-redis-credentials` | Nombre del secreto. Si lo cambias, cambia también el `urlPath` del mapping: WireMock casa por ruta literal |
| `SECRETS_REDIS_GROUP` | vacío = el mismo que `SECRETS_GROUP` (`default`) | Grupo del secreto. **Normalmente no se toca**: ver §5.2 |
| `CACHE_ENABLED` | `true` | **El único interruptor.** A `false` no hay decorador, ni conexión a Redis, ni se lee el service credential |
| `CACHE_TTL_MINUTES` | `10` | Corto a propósito, para ver expirar una entrada sin esperar |

Valores del stack de `infra/`, por si hace falta conectarse a mano:

| | Valor |
|---|---|
| Redis en claro | `localhost:6379`, sin auth (usuario `default`) |
| Redis con TLS | `localhost:6380`, usuario `contentms`, password `contentms-local` |
| CA | `infra/conf/redis-tls/ca.crt` |
| Redis Commander | `http://localhost:8081`, `admin`/`admin` |

> **Esto solo funciona con el stack de `infra/`.** El de `deploy/docker-compose.yaml` **no sirve
> el service credential de Redis**: su stub solo trae el secreto `kv`, y su Redis no tiene TLS
> ni usuario de ACL. Como la caché viene encendida, arrancar contra ese stack **falla**, con un
> mensaje que dice justo esto. Dos opciones: usar `infra/`, que es el camino soportado, o
> arrancar con `CACHE_ENABLED=false` y trabajar sin caché.

---

## 5. Variables en `develop` y `production`

Los dos perfiles son idénticos en esto.

### 5.1 Obligatorias

| Variable | Valor | Nota |
|---|---|---|
| `SECRETS_URL` | `https://<guid>.<region>.secrets-manager.appdomain.cloud` | Ya era obligatoria. Ver `secret-manager.md` §7.2 |
| `IBM_CLOUD_API_KEY` | la API key de la service ID | La credencial con la que se lee el secreto. Sin default fuera de `local` |

Las del COS (`COS_ENDPOINT`, `COS_BUCKET_CMS_CONTENT`, `COS_LOCATION`) no cambian: ver
`README.md` §6.

### 5.2 Opcionales

| Variable | Cuándo ponerla |
|---|---|
| `SECRETS_REDIS_NAME` | Si el secreto no se llama `contentms-redis-credentials` |
| `SECRETS_REDIS_GROUP` | Solo si vive en un grupo distinto al del secreto `kv` |

> **La duda habitual: ¿`SECRETS_GROUP` o `SECRETS_REDIS_GROUP`?** Las dos, pero apuntan a
> secretos distintos: la primera al `kv` de las credenciales del COS, la segunda al
> `service_credentials` de Redis. **Configura `SECRETS_GROUP` y deja la otra vacía**: vacía
> hereda el grupo de la primera, que es lo normal. Solo se separan si DevOps los coloca en
> grupos distintos, y entonces la service ID necesita permiso sobre los dos.
>
> **En local no basta con la variable**: el stub de WireMock casa por ruta literal y sus
> mappings llevan `default` escrito dentro (`/secret_groups/default/...`). Cambiar el grupo
> sin editar el `urlPath` de los dos mappings da un 404 y la aplicación no arranca.

### 5.3 Las que ya no existen

`REDIS_HOST`, `REDIS_PORT` y `REDIS_PASSWORD` **se han eliminado**: los `cache.yaml` ya no
declaran `spring.data.redis.*`, y el host, el puerto, el usuario, la password y el TLS salen
únicamente del service credential.

Eso quita de en medio el matiz de precedencia que había antes (`SPRING_DATA_REDIS_PORT` frente a
`REDIS_PORT`), porque ya no hay dos sitios que definan lo mismo.

El fail-fast que daban esas variables en `production` —no arrancar sin saber dónde está Redis— lo
cubre ahora el propio secreto: con la caché activada es obligatorio, y si no se puede leer, la
aplicación no arranca.

> Si alguna vez hace falta apuntar a un Redis que no venga de un service credential, la vía son
> las variables de Spring directas (`SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_SSL_ENABLED=false`,
> …), que ganan al secreto por ser variables de entorno reales. Y si el secreto ni siquiera existe,
> `CACHE_ENABLED=false`.

### 5.4 La combinación que deja la caché sin conexión

`SECRETS_ENABLED=false` con la caché encendida **no aborta el arranque** —es la vía de escape
legítima para trabajar en local sin el stub—, pero deja `spring.data.redis.*` sin definir y Spring
Boot cae a sus propios defaults (`localhost:6379`, sin TLS). En un entorno real eso es una caché
que no cachea nada mientras todo responde 200, así que el arranque lo avisa con un WARN explícito.
Si lo ves fuera de tu máquina, es un fallo de configuración.

### 5.5 Rotación

El secreto se lee **una vez, al arrancar**. Si DevOps rota el service credential, hay que
**reiniciar el pod**: no hay refresco en caliente.

---

## 6. Lo que hay que pedirle a DevOps

1. Que exista el secreto de tipo `service_credentials` con el nombre y el grupo que espera
   `secrets.redis.*`, apuntando a la instancia de Databases for Redis.
2. La **autorización IAM de servicio a servicio** que permite a Secrets Manager emitir esa
   credencial (sin ella, crear el secreto falla).
3. Confirmación de que el endpoint `…private.databases.appdomain.cloud` se alcanza desde Code
   Engine (service endpoints y VRF habilitados).
4. Quién rota el secreto y con qué frecuencia, por lo del reinicio.

El detalle de estas cuatro está en [`credenciales-ibm-cloud.md`](credenciales-ibm-cloud.md) §10.4.

---

## 7. Si algo falla

| Síntoma | Causa probable | Qué mirar |
|---|---|---|
| **Todo responde 200 y `KEYS` está vacío** | El handshake TLS falla y se degrada a WARN | `SSLHandshakeException` en el log. Causa típica: se regeneraron los certificados y el secreto sirve la CA vieja → `./up.sh --regen-secret redis ibm-secret-manager` |
| La app conecta a `6379` con `user=default` | El interruptor está apagado | Busca `Service credential de Redis desactivado` en el log |
| `No se pudo leer el service credential de Redis ...` | No existe el secreto, o el nombre/grupo no cuadran | `./up.sh ibm-secret-manager` lo genera. Para arrancar sin él: `CACHE_ENABLED=false` |
| `WRONGPASS` en un WARN | El usuario o la password del secreto no coinciden con el ACL de Redis | `up.sh` los sustituye en `services/redis.yaml` y en la plantilla desde las mismas variables; si has editado el mapping a mano, revisa los dos |
| `Invalid CA Certificate File/Directory` en `redis-cli` | Git Bash convirtió `/tls/ca.crt` en ruta de Windows | Antepón `MSYS_NO_PATHCONV=1` |
| No arranca y estoy usando `deploy/docker-compose.yaml` | Ese stack no sirve el service credential de Redis: solo el `kv` | Levanta el de `infra/` (`./up.sh redis ibm-secret-manager`), o `CACHE_ENABLED=false` |
| `up.sh` aborta con `hace falta openssl` | No está en el PATH | Instalarlo, o trabajar sin TLS con `CACHE_ENABLED=false` |
| He editado el secreto y no cambia nada | WireMock cachea los mappings, y la app lee el secreto una vez | `./reload-secret.sh` **y** reiniciar la aplicación |

Más casos, en [`infra/README.md`](infra/README.md).
