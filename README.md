# ContentMS

Microservicio de contenido estático sobre **IBM Cloud Object Storage**, para desplegar en
IBM Code Engine. Implementa la [HU-211](HU-211.md):

| Endpoint | Qué hace |
|---|---|
| `POST /v1/save-content` | Guarda un archivo en el COS de CMS bajo `/partnerId/Object` |
| `GET /v1/content-loaded?context_url=<partnerId>/<archivo>` | Devuelve el archivo como binario, sin cabeceras |

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

## 4. MinIO y el bucket

El compose levanta MinIO **y crea el bucket solo**: no hay ningún paso manual. Lo hace un
contenedor `minio-init` que espera al healthcheck de MinIO y lanza `mc mb --ignore-existing`,
así que es idempotente y puedes repetir el `up` sin romper nada.

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
podman ps                                    # contentms-minio debe estar "healthy"
podman logs contentms-minio-init             # "Bucket cms-content listo (privado)"
```

`contentms-minio-init` queda en `Exited (0)`. **Eso es lo correcto**: es un contenedor de un
solo uso que crea el bucket y termina.

Consola web de MinIO: [http://localhost:9001](http://localhost:9001) (`minioadmin` / `minioadmin`).

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
  -F 'file=@postman/image1.png;type=image/png'
# -> 201  {"returnCode":"201","message":"Created"}

# Descargar: sin ninguna cabecera, y con el socio dentro del context_url
curl -i "http://localhost:8080/v1/content-loaded?context_url=12345/image1.png" \
  -o salida.png
# -> 200, Content-Type: image/png, y salida.png idéntico al original
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
| Falta `context_url` | 400 | `MISSING_REQUIRED_PARAMETER` |
| `context_url` sin el prefijo `<partnerId>/` | 400 | `INVALID_OBJECT_PATH` |
| `context_url` con `../` | 400 | `INVALID_OBJECT_PATH` |
| `jsonString` no es JSON válido | 400 | `INVALID_JSON_STRING` |
| El archivo no existe | 404 | `CONTENT_NOT_FOUND` |
| MIME no admitido por el bucket | 422 | `UNSUPPORTED_CONTENT_TYPE` |
| Archivo mayor que el límite | 413 | `FILE_TOO_LARGE` |
| El COS no responde | 503 | `STORAGE_UNAVAILABLE` |

El GET no lleva cabeceras: `context_url` es su única entrada y ya identifica al socio
(`12345/image1.png`). Eso significa que **el GET no aísla a un socio de otro**: quien conozca
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
│   └── storage/       FileStorage (puerto), ObjectKey, políticas
├── application/       Casos de uso (CQRS), cero Spring
│   ├── commands/      SaveContentCommand
│   ├── queries/       GetContentLoadedQuery
│   └── usecases/      Los dos handlers
└── infrastructure/    Todo Spring vive aquí
    ├── rest/          Controller, ApiExceptionHandler, ErrorResponse
    ├── storage/       CosFileStorage (adaptador del puerto)
    ├── configurations/
    └── correlation/   CorrelationContext + filtro
```

`domain` y `application` no importan Spring en ningún sitio: se convierten en beans mediante
las anotaciones propias `@DomainComponent` / `@ApplicationComponent` y un `@ComponentScan`
filtrado en `UseCaseConfig`.

---

## 8. Fuera de alcance

Cosas que la HU-211 menciona y **no** están implementadas:

- **Caché de Redis** (colección `Components`). El puerto `FileStorage` admite un decorador de
  caché sin tocar los casos de uso; ese es el sitio.
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
4. **El GET va sin cabeceras.** La HU declara `correlation_id`, `request_id` y `_p`
   obligatorios también en el GET, y pide devolverlos en la respuesta. Aquí se suprimen a
   propósito: el GET es una descarga con una sola entrada, `context_url`, que ya lleva el
   socio delante (`12345/image1.png`). Consecuencia a validar con quien escribió la HU: al
   no haber `_p`, el GET deja de aislar a un socio de otro (ver §5) y sus líneas de log salen
   sin correlación. El POST mantiene las tres cabeceras tal como las pide la HU.
