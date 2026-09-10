# ContentMS — rama `feature/only-cache`

**Esta rama no es el servicio.** Es una aplicación reducida a un solo endpoint, cuyo único
propósito es **validar el comportamiento de la conexión a Redis**: el service credential de IBM
Cloud Secrets Manager, el TLS con CA autofirmada, el usuario de ACL y la caché.

Todo lo relacionado con el object storage —el COS, MinIO, la subida de ficheros, las políticas de
bucket— **no existe aquí**. Para eso, `main`.

| Endpoint | Qué hace |
|---|---|
| `GET /v1/cache/{id}` | Devuelve un string aleatorio asociado al `id` y lo cachea |

**La prueba es la igualdad:** dos llamadas con el mismo `id` tienen que devolver **el mismo**
string. Si devuelven valores distintos, la caché no está funcionando. Nada más que mirar.

Spring Boot 3.5.3 · Java 21 · Maven · arquitectura hexagonal (`domain` → `application` → `infrastructure`).

Las credenciales de Redis salen de **IBM Cloud Secrets Manager** al arrancar; en local, de un
stub que habla su misma API. El mecanismo completo, en
**[secret-manager.md](secret-manager.md)**; los fundamentos, en
**[credenciales-ibm-cloud.md](credenciales-ibm-cloud.md)**; el paso a paso de la prueba y las
variables por entorno, en **[redis-instruction.md](redis-instruction.md)**.

---

## 1. Requisitos

- **JDK 21** (`JAVA_HOME` apuntando a él). No hace falta instalar Maven: el proyecto trae el wrapper.
- **Podman o Docker**, y **`openssl`** — `infra/up.sh` genera con él los certificados del Redis
  con TLS.

> El wrapper es del tipo **`only-script`**: `.mvn/wrapper/maven-wrapper.properties` declara qué
> Maven usar y `mvnw` lo descarga la primera vez a `~/.m2/wrapper`. **En el repo no hay ningún
> `.jar`**. La primera compilación necesita salida a `repo1.maven.org`.

---

## 2. Levantar el entorno local

`infra/` **no cambia respecto a `main`**: el mismo stack sirve para las dos ramas. Aquí solo
hacen falta dos de sus servicios — **MinIO ya no**:

```bash
cd infra
./up.sh redis ibm-secret-manager
```

Eso deja levantado:

- **Redis en dos puertos**: `6379` en claro (para Redis Commander y el healthcheck) y **`6380`
  con TLS**, que es el que usa la aplicación, con el usuario de ACL `contentms`.
- **Un WireMock** en el `8090` que emula IBM Cloud Secrets Manager y sirve el service credential
  de Redis, con la CA dentro.
- **Redis Commander** en el `8081` (`admin`/`admin`), para mirar las claves sin `redis-cli`.

El stub sigue sirviendo también un secreto `kv` — esta rama **lo ignora**, ver §5.

---

## 3. Compilar y arrancar

```bash
./mvnw clean package
java -jar target/content-ms-1.0.0.jar --spring.profiles.active=local
```

En Windows: `.\mvnw.cmd clean package`.

El servicio escucha en `http://localhost:8080`. Swagger UI en
[`/swagger-ui.html`](http://localhost:8080/swagger-ui.html).

En el arranque tienen que aparecer estas dos líneas:

```
Sin secreto kv (secrets.name vacio): las credenciales del COS tienen que llegar por variable de entorno
Secrets Manager: conexion a Redis tomada del service credential 'contentms-redis-credentials'
  (grupo 'default'); 8 propiedades [...]
```

La primera confirma que el camino `kv` está desactivado a propósito. La segunda, que la conexión
salió del secreto.

---

## 4. Probar

### La prueba central

```bash
curl -s http://localhost:8080/v1/cache/42; echo
curl -s http://localhost:8080/v1/cache/42; echo
curl -s http://localhost:8080/v1/cache/43; echo
```

Esperado: **las dos primeras iguales**, la tercera distinta.

```
7fe434bd-7252-4c59-b7c8-17a3d0238237
7fe434bd-7252-4c59-b7c8-17a3d0238237
83d348f5-187c-4c85-8ebf-cd8b3e50012c
```

En el log, `Valor GENERADO` aparece **una sola vez por id**. Si sale en cada petición, la caché
no está interceptando.

### Que va por TLS, y no por el puerto en claro

Que haya valores iguales no dice por dónde se guardaron. Esto sí:

```bash
podman exec infra-redis redis-cli -p 6379 CLIENT LIST
```

Tiene que aparecer la aplicación en **`laddr=…:6380`** con **`user=contentms`** — el puerto y el
usuario que vienen del secreto. Si la vieras en `6379` con `user=default`, estarías probando otra
cosa.

### Las claves

```bash
podman exec infra-redis redis-cli --tls --cacert /tls/ca.crt -p 6380 \
  --user contentms --pass contentms-local KEYS 'contentms:*'
```

```
contentms:cache::42
contentms:cache::43
```

> **En Git Bash, antepón `MSYS_NO_PATHCONV=1`**, o convertirá `/tls/ca.crt` en una ruta de
> Windows y `redis-cli` contestará `Invalid CA Certificate File/Directory`, que parece un
> problema de certificados y no lo es.

En DevX, donde no hay `exec`, la vía es Redis Commander en el `8081`.

### La prueba negativa, que es la que da valor

Sustituye el `certificate_base64` del secreto por el de otra CA, recarga el stub
(`cd infra && ./reload-secret.sh`) y **reinicia la aplicación** — el secreto se lee una sola vez,
al arrancar.

Esperado: **HTTP 200 en todo, y un valor distinto en cada llamada.**

```
42 -> eccbd9fe-d8b7-4538-a63b-230be1b4b76c [HTTP 200]
42 -> 83cfa76e-fd5e-4794-9d72-d1da15b6f7b9 [HTTP 200]
```

Con `SSLHandshakeException: PKIX path building failed` en el log. **Ese es el fallo silencioso
que motiva toda esta rama**: `CacheConfig.errorHandler()` degrada el error a un WARN y
`management.health.redis.enabled` está en `false`, así que ni el código HTTP ni el health se
enteran. Aquí al menos se nota en la respuesta; en `main`, con el binario sirviéndose del COS, no
se notaría nada.

Para volver al estado bueno: `cd infra && ./up.sh --regen-secret redis ibm-secret-manager`.

### Sin caché

```bash
CACHE_ENABLED=false java -jar target/content-ms-1.0.0.jar --spring.profiles.active=local
```

Cada llamada devuelve un valor nuevo, y en el arranque sale `Cache desactivada`: **ni siquiera se
lee el service credential**, porque sin caché no hay conexión que configurar.

---

## 5. Configuración

### El secreto `kv` está desactivado, y listo para usarse

Esta rama no consume ningún secreto `kv`: su única credencial de servicio es la de Redis, y esa
viene en un `service_credentials`. Así que en `parameters/<perfil>/secrets.yaml` las dos claves
que lo localizan están **comentadas**:

```yaml
  # name: ${SECRETS_NAME:contentms-secrets}
  # group: ${SECRETS_GROUP:default}
```

**El código que las lee está intacto** (`IbmSecretsManagerSource`). Empezar a usar un `kv` son
dos pasos:

1. descomentar esas dos líneas;
2. consumir sus claves como `${MI_VARIABLE}` desde cualquier YAML del perfil.

No hay paso 3: no se toca Java. Las claves del secreto se llaman igual que las variables de los
YAML, que es lo que hace que no haya fricción.

### Variables

| Variable | Default | Para qué |
|---|---|---|
| `CACHE_ENABLED` | `true` | **El único interruptor.** A `false` no hay decorador, ni conexión a Redis, ni se lee el service credential |
| `CACHE_TTL_MINUTES` | `10` local · `60` develop · `1440` production | Minutos que vive una entrada |
| `SECRETS_ENABLED` | `true` | A `false` no se consulta Secrets Manager |
| `SECRETS_URL` | el stub en `local`; **sin default** fuera | Endpoint de la instancia |
| `SECRETS_IAM_URL` | el stub en `local`; `https://iam.cloud.ibm.com` fuera | Emisor del token IAM |
| `IBM_CLOUD_API_KEY` | valor falso en `local`; **sin default** fuera | La credencial del arranque |
| `SECRETS_REDIS_NAME` | `contentms-redis-credentials` | Nombre del service credential |
| `SECRETS_REDIS_GROUP` | `default` | Grupo que lo contiene |

**No hay `REDIS_HOST`, `REDIS_PORT` ni `REDIS_PASSWORD`**: el host, el puerto, el usuario de
ACL, la password y la CA del TLS salen del service credential, que es el único sitio que define
`spring.data.redis.*`.

En un despliegue real solo hay que poner **dos**: `SECRETS_URL` e `IBM_CLOUD_API_KEY`.

Detalle completo por entorno en [redis-instruction.md](redis-instruction.md) §5.

---

## 6. Estructura

Se conserva la arquitectura hexagonal de `main`, con un solo caso de uso:

```
domain/
  values/ValueGenerator            El puerto: dado un id, un valor
  errors/                          La jerarquia base + InvalidUuidHeaderError
application/
  queries/GetCachedValueQuery
  usecases/GetCachedValueQueryHandler
  interfaces/                      Contratos Command/Query que despacha el mediador
infrastructure/
  values/RandomValueGenerator      Adaptador real: genera un UUID
  values/CachedValueGenerator      Decorador @Primary con @Cacheable  <- lo que se valida
  rest/controllers/cache/v1/       El endpoint
  configurations/cache/            RedisCacheManager y el errorHandler
  secrets/                         Lectura de los secretos al arrancar
  correlation/, web/               correlation_id en el MDC
```

**La caché decora el puerto, no el caso de uso.** Es la misma decisión que en `main`, y es lo que
mantiene a `domain` y `application` sin una sola línea sobre Redis: `GetCachedValueQueryHandler`
pide el valor y no sabe si vino de la caché o se acaba de generar.

---

## 7. Qué NO hay en esta rama

- Nada de object storage: ni COS, ni MinIO, ni subida de ficheros, ni políticas de bucket.
- Ningún secreto `kv` activo (§5).
- Sin suite de tests: `src/test` no existe, igual que en `main`.

`infra/` es idéntico a `main` a propósito: el mismo stack local sirve para las dos ramas.
