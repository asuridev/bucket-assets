# ContentMS

Microservicio de contenido estático sobre **IBM Cloud Object Storage**, para desplegar en
IBM Code Engine. Implementa la [HU-211](HU-211.md):

| Endpoint | Qué hace |
|---|---|
| `POST /v1/save-content` | Guarda un archivo en el COS de CMS bajo `/partnerId/Object` |
| `GET /v1/content-loaded?context_url=<partnerId>/<archivo>` | Devuelve el archivo como binario; sin cabeceras obligatorias |

Spring Boot 3.5.3 · Java 21 · Maven · arquitectura hexagonal (`domain` → `application` → `infrastructure`).

---

## 1. Requisitos

- **JDK 21** (`JAVA_HOME` apuntando a él). No hace falta instalar Maven: el proyecto trae el wrapper.
- **Podman o Docker**: el servicio siempre habla con un object storage de verdad, y en
  desarrollo ese object storage es el MinIO del compose (sección 4).

---

## 2. Compilar

El wrapper descarga Maven la primera vez, así que ese primer build tarda más.

**Linux / macOS / Git Bash**
```bash
./mvnw clean package
```

**Windows (PowerShell o CMD)**
```powershell
.\mvnw.cmd clean package
```

Genera `target/content-ms-1.0.0.jar`.

> A partir de aquí, cada bloque muestra Linux y Windows. Lo único que cambia es
> `./mvnw` ↔ `.\mvnw.cmd`; los argumentos son idénticos.

---

## 3. Arrancar

Para desarrollo hay **un solo perfil**, `local`. El perfil se pasa como argumento, no como
variable de entorno, para que el comando sea el mismo en todas las shells.

### Perfil `local` — contra MinIO

No hay almacén en memoria: `local` usa el mismo adaptador y el mismo SDK que producción,
cambiando solo el endpoint y la forma de firmar (HMAC en vez de IAM). Así lo que pasa en
local es lo que pasará en COS. **Necesita MinIO levantado** (siguiente sección).

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

### Desde el jar

Igual en ambas plataformas:

```bash
java -jar target/content-ms-1.0.0.jar --spring.profiles.active=local
```

El servicio escucha en `http://localhost:8080`. Swagger UI en
[`/swagger-ui.html`](http://localhost:8080/swagger-ui.html).

---

## 4. MinIO, el bucket y Redis

El compose levanta cuatro cosas: **MinIO**, un contenedor `minio-init` que **crea el bucket
solo** (espera al healthcheck de MinIO y lanza `mc mb --ignore-existing`, así que es
idempotente y puedes repetir el `up` sin romper nada), **Redis**, que es la caché del GET, y
**Redis Commander**, una UI web para mirar las claves de esa caché sin `redis-cli`.

Redis va sin volumen a propósito: una caché no tiene que sobrevivir a un `down`, y así cada
arranque parte en frío, que es lo que se quiere para probarla.

### Con Podman

```bash
podman-compose -f deploy/docker-compose.yaml up -d      # levantar
podman-compose -f deploy/docker-compose.yaml down       # parar
podman-compose -f deploy/docker-compose.yaml down -v    # parar y borrar los objetos
```

> Usa **`podman-compose`** (con guion, el de Python), no `podman compose` (con espacio).
> Este último delega en el `docker-compose.exe` de WindowsApps, que necesita el named pipe
> de la API Docker; si `win-sshproxy.exe` no arranca, ese pipe no existe y falla con
> `error during connect`. `podman-compose` habla con el CLI de podman y no depende de él.

### Con Docker

```bash
docker compose -f deploy/docker-compose.yaml up -d
docker compose -f deploy/docker-compose.yaml down
```

### Comprobar que arrancó

```bash
podman ps                                    # contentms-minio, -redis y -redis-ui arriba
podman logs contentms-minio-init             # "Bucket cms-content listo (privado)"
podman exec contentms-redis redis-cli ping   # PONG
```

`contentms-minio-init` queda en `Exited (0)`. **Eso es lo correcto**: es un contenedor de un
solo uso que crea el bucket y termina.

Consola web de MinIO: [http://localhost:9001](http://localhost:9001) (`minioadmin` / `minioadmin`).
Redis Commander: [http://localhost:8081](http://localhost:8081), con la conexión `local` ya
configurada: no hay que añadirla a mano. Pide login (`admin` / `admin`) — es un formulario
que emite un JWT, no basic auth, así que sin credenciales la API responde `401`.

### En DevX

DevX solo deja exponer **HTTP**, y encima como subpath de un host compartido:

```
https://devx-cardif04.staging.echonet/user/<usuario>/http/8080/    # el servicio
https://devx-cardif04.staging.echonet/user/<usuario>/http/9001/    # consola de MinIO
https://devx-cardif04.staging.echonet/user/<usuario>/http/8081/    # Redis Commander
```

Ahí no hay `podman exec` ni puerto 6379, así que **Redis Commander es la única forma de ver
la caché**.

**1. Prepara el entorno.** Copia [`deploy/.env.devx.example`](deploy/.env.devx.example) a
`deploy/.env.devx` (ignorado por git) y edítalo: sustituye `<usuario>` por el tuyo (`j31399`)
y **cambia `REDIS_UI_USER` / `REDIS_UI_PASSWORD`** — esa URL es alcanzable y detrás hay una
consola con acceso total a la caché.

```bash
cp deploy/.env.devx.example deploy/.env.devx
podman-compose -f deploy/docker-compose.yaml --env-file deploy/.env.devx up -d
```

**2. Publica el puerto.** En el panel **PORTS** haz *Add Port* del **8081**; si no, no tiene
forwarded address.

**3. Averigua si el proxy reenvía el prefijo**, antes de fiarte de nada. Abre la consola de
MinIO por su URL de DevX (`…/http/9001/`) y mira en DevTools → Network a qué ruta pide los
assets:

| Los assets van a… | Significa | `REDIS_UI_BASE_PATH` |
|---|---|---|
| `/user/<usuario>/http/9001/static/…` | el prefijo llega al contenedor | `/user/<usuario>/http/8081` |
| `/static/…` (404 contra la raíz del host) | DevX lo recorta | vacío |

**4. Aplica el valor** y recrea solo ese contenedor:

```bash
podman-compose -f deploy/docker-compose.yaml --env-file deploy/.env.devx up -d redis-ui
```

**5. Abre la UI** en `https://devx-cardif04.staging.echonet/user/<usuario>/http/8081/`. Con
prefijo puesto, Redis Commander responde **solo** bajo esa ruta y la raíz da `401`; sin
prefijo, responde en la raíz. Si te encuentras un `401` o una página en blanco donde
esperabas el login, el valor está al revés: cámbialo y repite el paso 4.

**6. Comprueba la caché** igual que en local (§8), pero pidiendo el GET contra
`https://devx-cardif04.staging.echonet/user/<usuario>/http/8080/v1/content-loaded?context_url=…`
y mirando la clave en la UI en vez de con `redis-cli`.

---

## 5. Probar

### Con Postman

En [`postman/`](postman/) hay una colección con 10 peticiones (flujo principal, 7 casos de
error y salud) y un entorno para localhost. Importa los dos JSON y selecciona el entorno
*ContentMS - local*.

Ojo: hay que **seleccionar el archivo a mano** en las peticiones con `multipart` — Postman no
guarda rutas dentro de la colección. Está detallado en [`postman/README.md`](postman/README.md).

### Con curl

Los identificadores van fijos a propósito: el servicio no valida su formato, así que
cualquier cadena sirve y el ejemplo funciona igual en Git Bash, PowerShell y Linux. Si
quieres generarlos, en Linux es `$(uuidgen)` y en PowerShell `$(New-Guid)` — pero **en Git
Bash sobre Windows `uuidgen` no existe**.

```bash
# Subir
curl -i -X POST http://localhost:8080/v1/save-content \
  -H "correlation_id: 11111111-1111-1111-1111-111111111111" \
  -H "request_id: 22222222-2222-2222-2222-222222222222" \
  -H "_p: 12345" \
  -F 'jsonString={"fileName":"image1.png","partnerId":"12345"}' \
  -F 'file=@postman/image1.png'
# -> 201  {"returnCode":"201","message":"Created"}
# No hace falta declarar el tipo del archivo: sale de la extension de fileName.

# Descargar: sin ninguna cabecera, y con el socio dentro del context_url
curl -i "http://localhost:8080/v1/content-loaded?context_url=12345/image1.png" \
  -o salida.png
# -> 200, Content-Type: image/png, y salida.png idéntico al original

# Opcionalmente, trazado: si se mandan, tienen que ser UUID y vuelven en la respuesta
curl -i "http://localhost:8080/v1/content-loaded?context_url=12345/image1.png" \
  -H "correlation_id: 11111111-1111-1111-1111-111111111111" \
  -H "request_id: 22222222-2222-2222-2222-222222222222" \
  -o salida.png
# -> 200, y las dos cabeceras de vuelta
```

En **PowerShell**, `curl` es un alias de `Invoke-WebRequest` y no acepta estos argumentos.
Usa `curl.exe` explícitamente (viene con Windows 10+), o lanza los comandos desde Git Bash.

### Ver el objeto dentro del bucket

```bash
podman exec contentms-minio mc alias set l http://127.0.0.1:9000 minioadmin minioadmin
podman exec contentms-minio mc ls --recursive l/cms-content
# 67B STANDARD 12345/image1.png     <- la estructura /partnerId/Object de la HU
```

### Qué esperar en los errores

Todos devuelven el cuerpo `errorHeader` / `errorDetail` del contrato:

| Caso | Código | `errorDetail.code` |
|---|---|---|
| Falta `correlation_id`, `request_id` o `_p` **en el POST** | 400 | `MISSING_REQUIRED_HEADER` |
| `correlation_id` o `request_id` **en el GET** no es un UUID | 400 | `INVALID_UUID_HEADER` |
| Falta `context_url` | 400 | `MISSING_REQUIRED_PARAMETER` |
| `context_url` sin el prefijo `<partnerId>/` | 400 | `INVALID_OBJECT_PATH` |
| `context_url` con `../` | 400 | `INVALID_OBJECT_PATH` |
| `jsonString` no es JSON válido | 400 | `INVALID_JSON_STRING` |
| El archivo no existe | 404 | `CONTENT_NOT_FOUND` |
| MIME no admitido por el bucket | 422 | `UNSUPPORTED_CONTENT_TYPE` |
| Archivo mayor que el límite | 413 | `FILE_TOO_LARGE` |
| El COS no responde | 503 | `STORAGE_UNAVAILABLE` |

El GET no lleva ninguna cabecera obligatoria: `context_url` es su única entrada exigida y ya
identifica al socio (`12345/image1.png`). `correlation_id` y `request_id` se admiten como
opcionales —si se envían tienen que ser un UUID canónico y se devuelven tal cual—, pero `_p`
no existe allí. Eso significa que **el GET no aísla a un socio de otro**: quien conozca
el id ajeno puede pedir `context_url=99999/x.pdf` y lo obtendrá. La guarda contra `../` sigue
puesta —impide salirse del espacio de socios—, pero el aislamiento real tendrá que venir de la
capa de autorización que aún no está cableada.

---

## 6. Configuración

Cada perfil tiene un `application-<perfil>.yaml` que **solo hace `spring.config.import`** de
fragmentos en `src/main/resources/parameters/<perfil>/`. Así el diff entre entornos se revisa
concepto a concepto.

| Perfil | Almacén | Para qué |
|---|---|---|
| `local` | MinIO (HMAC) | Desarrollo contra el SDK real |
| `develop` | IBM COS (IAM) | Entorno de desarrollo |
| `production` | IBM COS (IAM) | Producción |

No hay perfil sin almacén: el adaptador `CosFileStorage` es el único que implementa el
puerto `FileStorage` y está siempre activo. Lo único que discrimina entre entornos es
`storage.auth-mode` (`hmac` en `local`, `iam` en `develop`/`production`).

### Variables para MinIO (perfil `local`)

Todas traen default, así que el perfil `local` funciona sin exportar nada mientras uses el
compose tal cual.

| Variable | Default | Para qué |
|---|---|---|
| `MINIO_ENDPOINT` | `http://localhost:9000` | API S3 de MinIO |
| `MINIO_REGION` | `us-east-1` | Región con la que se firma |
| `MINIO_ACCESS_KEY` | `minioadmin` | Access key HMAC |
| `MINIO_SECRET_KEY` | `minioadmin` | Secret key HMAC |
| `MINIO_BUCKET_CMS_CONTENT` | `cms-content` | Bucket; el compose lo crea con este mismo valor |

### Variables de la caché

| Variable | Default | Para qué |
|---|---|---|
| `CACHE_ENABLED` | `true` | A `false` quita el decorador: el servicio habla solo con el COS |
| `CACHE_TTL_MINUTES` | `10` local · `60` develop · `1440` production | Minutos que vive una entrada |
| `REDIS_HOST` | `localhost` (sin default en `production`) | Host de Redis |
| `REDIS_PORT` | `6379` (sin default en `production`) | Puerto de Redis |

En `production` `REDIS_HOST` y `REDIS_PORT` no traen default, igual que las credenciales del
COS: con la caché activada, arrancar sin saber dónde está Redis es un fallo de despliegue y
debe verse al arrancar, no en la primera petición.

### Variables del compose (no las lee el servicio)

Estas solo las consume `deploy/docker-compose.yaml`. En local no hace falta exportar ninguna;
en DevX van en `deploy/.env.devx` (plantilla en `deploy/.env.devx.example`).

Los puertos publicados van **literales** en el compose, sin variable: hay implementaciones de
compose (la de DevX) que no sustituyen dentro de `ports:` y parten el valor por `:`, con lo que
`${REDIS_PORT:-6379}:6379` acaba en `ValueError: invalid literal for int(): '-6379}'`.

| Variable | Default | Para qué |
|---|---|---|
| `MINIO_PUBLIC_URL` | `http://localhost:9001` | `MINIO_BROWSER_REDIRECT_URL` de la consola. Sin barra final; no puede ir vacía, MinIO la valida como URL |
| `REDIS_UI_BASE_PATH` | vacío | `URL_PREFIX` de la UI. Solo si el proxy reenvía el prefijo (ver §4) |
| `REDIS_UI_USER` | `admin` | Basic auth de la UI |
| `REDIS_UI_PASSWORD` | `admin` | Basic auth de la UI. **Cámbiala en DevX** |

### Variables para COS

`develop` y `production` no traen valores por defecto en lo obligatorio, a propósito: la app
se niega a arrancar sin credenciales en vez de fallar en la primera petición.

| Variable | Obligatoria | Ejemplo |
|---|---|---|
| `COS_ENDPOINT` | sí | `https://s3.us-south.cloud-object-storage.appdomain.cloud` |
| `COS_API_KEY` | sí | API key de IAM |
| `COS_SERVICE_INSTANCE_ID` | sí | CRN de la instancia |
| `COS_BUCKET_CMS_CONTENT` | sí | Nombre del bucket |
| `COS_LOCATION` | no (`us-south` en develop) | `us-south` |
| `COS_MAX_SIZE_MB` | no (`10`) | `10` |
| `COS_ALLOWED_CONTENT_TYPES` | no | `image/png,image/jpeg,application/pdf` |

```bash
export COS_ENDPOINT=https://s3.us-south.cloud-object-storage.appdomain.cloud
export COS_API_KEY=...
export COS_SERVICE_INSTANCE_ID=crn:v1:bluemix:public:cloud-object-storage:...
export COS_BUCKET_CMS_CONTENT=cms-content
java -jar target/content-ms-1.0.0.jar --spring.profiles.active=develop
```
```powershell
$env:COS_ENDPOINT = "https://s3.us-south.cloud-object-storage.appdomain.cloud"
$env:COS_API_KEY = "..."
$env:COS_SERVICE_INSTANCE_ID = "crn:v1:bluemix:public:cloud-object-storage:..."
$env:COS_BUCKET_CMS_CONTENT = "cms-content"
java -jar target/content-ms-1.0.0.jar --spring.profiles.active=develop
```

---

## 7. Estructura

```
src/main/java/com/bnpparibas/cardif/cloud/contentms/
├── domain/            Java puro, cero Spring
│   ├── errors/        DomainException + errores tipados
│   └── storage/       FileStorage (puerto), ObjectKey, ContentTypes, políticas
├── application/       Casos de uso (CQRS), cero Spring
│   ├── commands/      SaveContentCommand
│   ├── queries/       GetContentLoadedQuery
│   └── usecases/      Los dos handlers
└── infrastructure/    Todo Spring vive aquí
    ├── rest/          Controller, ApiExceptionHandler, ErrorResponse
    ├── storage/       CosFileStorage (adaptador del puerto) +
    │                  CachedFileStorage (decorador de caché, @Primary)
    ├── configurations/  usecase/, storage/, cache/
    └── correlation/   CorrelationContext + filtro
```

`domain` y `application` no importan Spring en ningún sitio: se convierten en beans mediante
las anotaciones propias `@DomainComponent` / `@ApplicationComponent` y un `@ComponentScan`
filtrado en `UseCaseConfig`.

---

## 8. La caché del GET

El paso 1 del HU-211 —mirar Redis antes de ir al COS— está implementado. La primera petición
a un recurso lo baja del bucket y lo deja en Redis; la segunda y siguientes se sirven de la
caché hasta que expire el TTL.

**Dónde está.** En `CachedFileStorage`, un decorador que implementa el mismo puerto
`FileStorage` y está marcado `@Primary`. Los casos de uso reciben esa versión sin cambiar una
línea: `domain` y `application` no saben que Redis existe.

**Por qué decorar el puerto y no el caso de uso.** Por ese puerto pasan **los dos** endpoints,
así que `upload` y `delete` invalidan la clave gratis. Volver a subir el mismo `fileName`
nunca puede dejar el GET sirviendo los bytes viejos. Una caché colgada del handler del GET no
vería esa escritura.

**Claves.** El nombre de la caché es a la vez el prefijo en Redis:

```
contentms:content::cmsContent/12345/image1.png
```

así que `contentms:*` barre lo de este servicio sin tocar nada más de la instancia.

**Si Redis se cae, el servicio sigue.** Un fallo hablando con la caché degrada a *miss*: se
sirve del COS, se registra un `WARN` y ya. Detrás hay un almacén durable, así que no es motivo
para dejar de servir archivos. Por lo mismo, `management.health.redis.enabled` es `false` en
todos los perfiles: una caché inalcanzable no debe poner `/actuator/health` en `DOWN` y hacer
que Code Engine reinicie el pod.

> **Ojo al depurar:** esa degradación también se traga los fallos de serialización, así que
> una caché que no cachea **nada** tiene buen aspecto desde fuera. La prueba de que funciona
> es ver la clave en Redis, no recibir un 200.

**Comprobarlo:**

```bash
# 1. se puebla
curl -s -o /dev/null "http://localhost:8080/v1/content-loaded?context_url=12345%2Fimage1.png"
podman exec contentms-redis redis-cli KEYS 'contentms:*'
podman exec contentms-redis redis-cli TTL 'contentms:content::cmsContent/12345/image1.png'

# 2. la segunda petición no toca el bucket: se apaga MinIO y sigue devolviendo 200
podman stop contentms-minio
curl -s -D - -o /tmp/x.png "http://localhost:8080/v1/content-loaded?context_url=12345%2Fimage1.png" | head -3
podman start contentms-minio
```

Lo mismo sin terminal, en [Redis Commander](http://localhost:8081): conexión `local`, filtro
`contentms:*`, y la clave muestra su TTL. En DevX es la única vía, porque allí no hay `exec`
ni acceso al 6379.

Un recurso **no** cacheado, con MinIO apagado, da `503 STORAGE_UNAVAILABLE`: ese contraste es
lo que demuestra que el 200 anterior salió de la caché.

Otros dos detalles: los 404 no se cachean (`FileNotFoundError` es una excepción, no se guarda
nada, así que subir el archivo después se ve de inmediato), y `@Cacheable(sync = true)` hace
que N peticiones simultáneas al mismo archivo provoquen **una** descarga del COS.

---

## 9. Fuera de alcance

Cosas que la HU-211 menciona y **no** están implementadas:

- **MongoDB `OutPutsUrls`** — la propia HU la marca como temporal de migración.
- **Seguridad / 401.** La forma del error está en el contrato, pero no hay emisor: falta
  decidir si es un OAuth2 resource server, el API gateway de Code Engine, u otra cosa.

Además, tres puntos donde la HU es ambigua y se tomó una decisión — conviene validarlos con
quien la escribió:

1. **El GET no puede devolver dos cuerpos.** La HU tabula a la vez un `responseHeader` y el
   binario. El binario va en el cuerpo; `returnCode` y `message`, como headers HTTP.
2. **`partnerId` es opcional en el JSON** pero la ruta `/partnerId/Object` lo exige. En el
   POST se resuelve JSON → header `_p` → 400.
3. **Se añadió una guarda de path traversal** que la HU no pide. El `context_url` viene del
   cliente: sin validarlo, un `../otroSocio/x.pdf` se saldría del espacio de socios.
4. **El Content-Type se deduce de la extensión de `fileName`**, no de lo que declare el
   cliente: `image2.svg` se guarda como `image/svg+xml` aunque venga anunciado como
   `image/png`. El motivo es que el archivo se sirve por su clave, y esa clave la compone el
   mismo `fileName`; si el metadato no concordara, el GET devolvería un SVG etiquetado como
   PNG y el navegador no lo pintaría. Lo declarado sólo se usa como respaldo cuando la
   extensión es desconocida. No se miran los bytes del archivo: un PDF renombrado a `.png` se
   guardaría como `image/png`.
5. **En el GET las cabeceras son opcionales.** La HU declara `correlation_id`, `request_id`
   y `_p` obligatorios también en el GET, y pide devolverlos en la respuesta. Aquí no lo son:
   el GET es una descarga cuya única entrada exigida es `context_url`, que ya lleva el socio
   delante (`12345/image1.png`).
   - `correlation_id` y `request_id` se aceptan si vienen. Si vienen, tienen que ser un UUID
     canónico (`8-4-4-4-12` hexadecimal) o la petición se corta con un 400
     `INVALID_UUID_HEADER`: un identificador malformado no traza nada y contamina la
     correlación aguas abajo. Se valida con expresión regular y no con `UUID.fromString`,
     que acepta en silencio formas cortas como `1-1-1-1-1`. Los valores válidos se devuelven
     tal cual y trazan las líneas de log de esa petición; si no llegan, **no se genera
     ninguno** y la respuesta no los lleva, que es el comportamiento de siempre.
   - `_p` sigue sin existir en el GET. Consecuencia a validar con quien escribió la HU: sin
     él, el GET no aísla a un socio de otro (ver §5).

   El POST mantiene las tres cabeceras obligatorias tal como las pide la HU, y allí su valor
   se toma tal cual, sin exigir formato.
