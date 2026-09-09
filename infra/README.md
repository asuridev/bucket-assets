# `infra/` — stack a la carta para DevX

Un solo comando que pregunta qué necesitas, arma el `docker-compose.yaml` con **solo eso**, lo
levanta y termina imprimiendo credenciales y URLs.

```bash
./up.sh                        # menú interactivo
./up.sh redis mongo            # directo
./up.sh oracle                 # Oracle + su UI (DbGate)
./up.sh ibm-secret-manager     # solo la emulación de Secrets Manager
./up.sh --dry-run all          # solo genera el compose, para revisarlo
./up.sh --regen-secret ibm-secret-manager   # rehace el secreto del stub desde la plantilla
./reload-secret.sh             # recarga el secreto editado en el stub, sin recrear el contenedor
./down.sh                      # baja el stack (los datos sobreviven)
./down.sh -v                   # baja y borra los volúmenes (pide confirmación)
```

Cada servicio arrastra lo suyo automáticamente: **redis** → Redis Commander, **mongo** →
mongo-express (con su nginx delante), **minio** → creación del bucket, que se pregunta al vuelo,
**oracle** → DbGate, **ibm-secret-manager** → un WireMock que emula IBM Cloud Secrets Manager,
con su secreto generado a medida de este stack.

`all` es los cinco.

> Está pensado **solo para DevX**: siempre resuelve el prefijo `/user/<usuario>/http/<puerto>`
> con el que ese entorno publica cada puerto. Para trabajar en local están `deploy/` y
> `deploy-mongo/`.

## Puertos y credenciales

| Servicio | Puerto | Usuario / contraseña |
|---|---|---|
| Redis | 6379 | sin auth |
| Redis Commander (UI) | 8081 | `admin` / `admin` |
| MongoDB | 27017 | `admin` / `admin` |
| mongo-express (UI) | 8082 | `admin` / `admin` |
| Oracle | 1521 | `admin` / `admin` (usuario de aplicación, en `FREEPDB1`) |
| DbGate (UI) | 8083 | `admin` / `admin` |
| MinIO API | 9000 | `admin` / `adminadmin` |
| MinIO consola (UI) | 9001 | `admin` / `adminadmin` |
| Emulación de Secrets Manager | 8090 | sin auth — **no es una UI** |

**MinIO es la única excepción a `admin`/`admin`**: rechaza arrancar con una contraseña de menos
de 8 caracteres.

**El `admin`/`admin` de MongoDB lo crea la propia imagen**, con
`MONGO_INITDB_ROOT_USERNAME`/`MONGO_INITDB_ROOT_PASSWORD`: el entrypoint oficial activa `--auth`
por su cuenta y crea ese usuario con rol `root`. Pero **solo en la primera inicialización, con el
directorio de datos vacío** — se salta el init si ya encuentra `/data/db/WiredTiger`. Sobre un
volumen con datos previos la base `admin` se quedaría sin ningún usuario y la URI
`mongodb://admin:admin@mongo:27017/?authSource=admin` no autenticaría; el remedio es empezar
limpio con `./down.sh -v`.

**Y el `admin`/`admin` de Oracle, también**: `APP_USER`/`APP_USER_PASSWORD` crean un usuario
normal dentro de la PDB `FREEPDB1`, y **solo en la primera inicialización, con el volumen
`oracle-data` vacío**. Misma trampa que en Mongo y mismo remedio (`./down.sh -v`); el síntoma
aquí es `ORA-01017`. La contraseña de `SYS`/`SYSTEM` es la misma, pero la UI no la usa: para
trastear no hace falta `SYSDBA`.

Tras levantar, hay que hacer ***Add Port*** en el panel PORTS de la IDE con cada puerto de UI
(8081, 8082, 8083, 9001). Sin eso no hay forwarded address y la URL da 502.

**El 8090 es la excepción**: la emulación de Secrets Manager no la abre un navegador, la
consume la aplicación desde el propio workspace por `localhost`. No necesita *Add Port*, y de
hecho el perfil `local` del servicio ya apunta ahí por defecto (`SECRETS_URL`), así que no hay
que exportar nada.

## Qué hay en el directorio

```
images.json               TODAS las imágenes. Es el único sitio donde se cambia una versión.
.env                      DEVX_USER / DEVX_HOST. Lo crea up.sh la primera vez. No se commitea.
reload-secret.sh          Le dice al stub de Secrets Manager que relea el secreto del disco.
services/*.yaml           Un fragmento de compose por servicio, con marcadores __IMAGE_X__.
conf/default.conf.template  Plantilla del nginx que sirve mongo-express bajo el subpath.
conf/mongo-ui.conf        Generada por up.sh con tu prefijo dentro. No se commitea.
conf/secrets-manager-stub/  Los stubs de WireMock: mappings/iam-token.json y cr-token viajan
                          tal cual; mappings/secret-kv.json lo genera up.sh la primera vez
                          desde secret-kv.json.template, luego lo CONSERVA (se edita a
                          mano) y NO se commitea (ver abajo).
generated/docker-compose.yaml  Lo que up.sh arma y levanta. No se commitea.
```

`up.sh` resuelve **antes de ensamblar** las imágenes que necesita y aborta si falta alguna
clave en `images.json`. Es a propósito: una imagen vacía produce un compose inválido con un
error indescifrable.

Y las **descarga antes del `up`**, con tres reintentos, en vez de dejar que el `up` las baje:
una imagen de varios GB por el proxy corporativo se corta sola a media capa
(`unexpected EOF`), y al reintentar se reaprovecha lo ya descargado. Después del arranque
comprueba que los contenedores **existen de verdad** — se ha visto un `up -d` acabar con
código 0 sin crear nada — y aborta si falta alguno, para que el resumen de credenciales no
mienta.

Cada fragmento nuevo hay que registrarlo en el bloque de ensamblado de `up.sh`. El de Mongo son
tres `render`, y el orden importa porque es el que acaba en el compose:

```sh
render mongo.yaml
render mongo-express.yaml
render mongo-ui-proxy.yaml
```

El de Oracle son **dos**, no tres: DbGate no necesita nginx delante (ver más abajo).

```sh
render oracle.yaml
render oracle-ui.yaml
```

## La versión de MongoDB, y por qué ya no hay `mongo-init`

`images.json` fija **`mongo:8.3.8-noble`**, la imagen oficial. Antes pedía
`bitnami/mongodb:latest`, sin fijar, y la versión del servidor dependía del registro que
respondiera: el mirror corporativo de DevX resolvía ese `latest` a **MongoDB 4.4.26** y Docker Hub
a una 8.x. De ahí salía casi toda la complejidad que había aquí — 4.4 no trae `mongosh`, así que
el healthcheck encadenaba cuatro invocaciones, y `db.auth()` devuelve `1` en la shell legacy pero
`{ ok: 1 }` en `mongosh`, así que cualquier script tenía que aceptar las dos formas.

Con el tag fijado eso desaparece: `mongosh` está siempre, el healthcheck es una línea, y sobre
todo **el usuario `admin` lo crea el entrypoint de la imagen**, que además añade `--auth` solo.
Existía un servicio `mongo-init` (contenedor de un solo uso, con `network_mode: "service:mongo"`
para entrar por la *localhost exception* y 30 reintentos) porque Bitnami no lo garantizaba; ya no
hace falta y se ha eliminado.

> **Si el mirror de DevX no sirviera `mongo:8.3.8-noble`**, es un cambio de una línea: la clave
> `mongo` de `images.json`. Comprobar qué versión está corriendo de verdad:
> `docker exec infra-mongo mongosh --quiet --eval "db.version()"`.

**Migración desde el stack anterior:** el volumen viejo, `infra_mongo-data`, tenía el layout de
Bitnami (`/bitnami/mongodb`) y la imagen oficial lee `/data/db`. Por eso el volumen nuevo se llama
`mongo-data-v8`: reutilizar el otro habría dado un `mongod` con autorización activada y cero
usuarios. El viejo queda huérfano y se puede borrar:

```bash
podman volume rm infra_mongo-data      # o docker volume rm
```

**Un detalle de Bitnami que sí sobrevive:** `condition: service_healthy` lo cumple
`docker compose`, pero **`podman-compose` lo ignora** y arranca todo a la vez. Por eso
mongo-express lleva `restart: unless-stopped`: aborta si su primera conexión no autentica y no
reintenta por su cuenta, así que puede pillar a `mongod` todavía inicializándose.

## Por qué cada UI necesita algo distinto

DevX publica cada puerto como subpath y **recorta el prefijo** antes de reenviar al contenedor
(ver [`../DEVX-CLIENTES-WEB.md`](../DEVX-CLIENTES-WEB.md)). Eso obliga a tratar cada UI de una
forma:

- **Redis Commander** sirve en la raíz y pide sus assets con rutas relativas → funciona tal
  cual, con `URL_PREFIX` **vacío**.
- **mongo-express** usa rutas absolutas (`/public/…`) → sin ayuda cargaría sin estilos. Por eso
  lleva un nginx delante que reescribe el HTML, y `up.sh` genera su config con el prefijo
  dentro (y verifica que la sustitución ocurrió; si no, aborta).
- **DbGate** funciona tal cual, como Redis Commander, y por una razón que conviene dejar
  escrita porque no es evidente: pide sus assets con rutas **relativas** (`build/bundle.js`,
  `global.css`, sin barra inicial ni `<base href>`) y calcula la URL de su API con
  `window.location.origin + window.location.pathname`, así que el prefijo se lo da el propio
  navegador. Por eso **no** lleva nginx delante y **no** se define `WEB_ROOT`, que es su
  variable de subpath: montaría la app *bajo* el prefijo, y DevX lo recorta antes de reenviar,
  con lo que todo daría 404. Es el mismo motivo por el que `ME_CONFIG_SITE_BASEURL` no sirve
  en mongo-express.
- **MinIO** necesita `MINIO_BROWSER_REDIRECT_URL` con la URL pública completa, o la consola
  redirige a `localhost`.
- **La emulación de Secrets Manager no es una UI** y por eso no necesita nada de esto: la
  consume la aplicación por `localhost:8090`, no un navegador a través del proxy.

## Oracle: por qué **sin** `faststart`, y por qué DbGate

`images.json` fija **`gvenzl/oracle-free:23.26.3-slim`**. Son las imágenes de la comunidad
publicadas en Docker Hub: se descargan **sin login y sin aceptar ninguna licencia**, a
diferencia de las de `container-registry.oracle.com`. Dos cosas de ese tag:

- **`slim`**: sin los componentes que un entorno de desarrollo no usa. Aun así son **0,85 GB de
  descarga** y **1,97 GB** ya descomprimida: es Oracle.
- **Sin `faststart`, y eso es deliberado.** Parece la opción obvia — la variante `-faststart`
  trae la base ya desplegada dentro de la imagen — pero con un volumen montado encima la base
  acaba **almacenada dos veces**: 5,12 GB de imagen **más** 3,0 GB de volumen, frente a
  1,97 + 3,0. En un workspace de DevX esos ~3 GB de más son la diferencia entre arrancar y un
  `no space left on device` a mitad de `sysaux01.dbf`. Y lo que se paga a cambio es poco: la
  imagen `slim` trae los datafiles comprimidos y los descomprime en el primer arranque en
  **7 segundos** (medido: `LISTA en 21s` de principio a fin). No crea la base desde cero, que
  es lo que sí tardaría minutos.
- **Versión completa fijada** (`23.26.3`) y no `23-slim`, que es un tag móvil: misma razón por
  la que `mongo` está fijado.

Ocupación total en el disco de Docker: **~5,0 GB** (1,97 de imagen + 3,0 de volumen), más
470 MB de DbGate. La señal de que Oracle está listo es `DATABASE IS READY TO USE!` en
`docker logs -f infra-oracle`, **no** que el contenedor aparezca `Up`.

El **1521 es TCP puro, así que desde DevX no se ve**: ese entorno solo publica HTTP bajo
subpath. Sirve para conectar desde el propio workspace (JDBC); desde el navegador la única vía
es la UI. Es exactamente la situación de Redis y de Mongo, y por eso ningún servicio de aquí se
añade sin su UI.

**La UI es DbGate**, y las dos alternativas se descartaron por lo mismo, que es lo que importa
en este entorno: **conseguir el driver de Oracle**.

- **DbGate** habla con Oracle en *thin mode*, con un driver JS puro. No necesita Oracle Instant
  Client ni descargar ningún `.jar`: lo que trae la imagen es todo lo que hace falta. Además la
  conexión se preconfigura con variables de entorno (`CONNECTIONS`, `ENGINE_ora`, `SERVER_ora`,
  `SERVICE_NAME_ora`…), así que la UI abre ya conectada, y `LOGIN`/`PASSWORD` le ponen un login
  propio — sin ellas quedaría abierta a cualquiera del entorno que acierte el puerto.
- **CloudBeaver** es el cliente más completo, pero baja el `ojdbc` de Maven Central **en
  caliente**, la primera vez que se conecta. Si el proxy corporativo lo corta, no hay conexión
  y **no hay forma de meter el jar a mano**: en DevX no hay `exec`.
- **SQLPad** es el único con soporte nativo de subpath (`SQLPAD_BASE_URL`), pero su driver de
  Oracle es *thick mode* y exige el Instant Client dentro de la imagen, que la oficial no trae.
  Habría que construir una propia, y en todo el repo no hay ningún `Dockerfile` a propósito.

Oracle pide **~1,5-2 GB de RAM**. Si el workspace no da para tanto, es un cambio de dos líneas:
la clave `oracle` de `images.json` a `gvenzl/oracle-xe:21-slim-faststart` y `ORACLE_SERVICE` de
`up.sh` a `XEPDB1` (en XE la PDB se llama así).

### Si hiciera falta una imagen más liviana

Todo medido, no estimado: descarga contra la API de Docker Hub, imagen y volumen con
`podman images` y `du -sh` dentro del contenedor.

| Imagen | Descarga | Imagen | + volumen | Motor |
|---|---|---|---|---|
| `oracle-free:23.26.3-slim` (la que usamos) | 0,85 GB | 1,97 GB | **5,0 GB** | 23ai |
| `oracle-free:23.26.3-slim-faststart` | 1,32 GB | 5,12 GB | **8,1 GB** | 23ai |
| `oracle-xe:11.2.0.2-slim-faststart` | 0,46 GB | 1,64 GB | ~2,6 GB | 11g (2011) |

La columna que importa en DevX es la tercera: es lo que acaba en el disco de Docker. **11g XE**
es la única realmente pequeña, pero es un motor de 2011: sin tipo `JSON`, sin `IDENTITY`, sin
`FETCH FIRST n ROWS` y sin PDBs — el servicio se llama `XE` a secas, no `XEPDB1`. Vale para
pinchar tablas; miente sobre lo que acepta un Oracle actual.

## El secreto del stub: se genera una vez, luego se edita a mano

`conf/secrets-manager-stub/mappings/secret-kv.json` sale de `secret-kv.json.template` la **primera
vez** que se levanta `ibm-secret-manager`, sustituyendo las credenciales de MinIO. **No es
cosmético**: el secreto lleva dentro `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` y son las que el
servicio usa de verdad para firmar contra MinIO en el perfil `local`. Aquí valen
`admin`/`adminadmin`, mientras que el compose de `deploy/` usa `minioadmin` — por eso cada entorno
tiene su copia y no se comparte el fichero.

Esas credenciales viven en **un solo sitio**, las variables `MINIO_USER`/`MINIO_PASSWORD` de
`up.sh`, desde donde se sustituyen en `services/minio.yaml`, `services/minio-init.yaml` y la
plantilla del secreto. Escritas a mano en los tres, cambiar una sola dejaría al servicio
firmando con unas y a MinIO esperando otras.

`up.sh` **aborta** si queda algún marcador `__MINIO_*__` sin sustituir, igual que hace con el
prefijo del nginx: servir marcadores literales daría un fallo mucho más tarde y sin pista.

La plantilla vive **fuera** de `mappings/` a propósito: WireMock aborta el arranque si encuentra
ahí un fichero que no sepa parsear.

### Cambiar los valores del secreto

El fichero generado **se conserva**: si ya existe, `./up.sh` no lo toca. Es el sitio donde se
editan los valores a mano — otras credenciales de MinIO, un `COS_API_KEY` real para probar, un
`REDIS_PASSWORD`. El ciclo es:

```bash
vi conf/secrets-manager-stub/mappings/secret-kv.json   # 1. editar
./reload-secret.sh                                     # 2. que el stub lo relea
# 3. reiniciar la aplicación
```

Los tres pasos hacen falta, y cada uno por un motivo distinto:

1. **No hay que recrear el contenedor.** `services/secrets-manager-stub.yaml` monta
   `../conf/secrets-manager-stub` dentro de `/home/wiremock`, así que el fichero editado ya es el
   que el contenedor ve. No está horneado en ninguna imagen — de hecho en todo el repo no hay
   ningún `Dockerfile`.
2. **Pero WireMock no vigila el disco.** Carga los mappings *en memoria* al arrancar, así que
   hasta que no se le pide releer sigue sirviendo lo viejo. `reload-secret.sh` hace
   `POST /__admin/mappings/reset`, que vuelve a cargar los mappings desde disco, y a continuación
   imprime lo que el stub sirve ahora — que es la única prueba real de que la edición llegó.
3. **Y la aplicación lee el secreto una sola vez, al arrancar.** `SecretsEnvironmentPostProcessor`
   no refresca (ver `CLAUDE.md`, sección *Secrets*), así que recargar el stub no basta: hay que
   reiniciar el servicio Spring Boot.

Para volver a los valores de la plantilla: `./up.sh --regen-secret ibm-secret-manager`.

## Si algo falla

| Síntoma | Causa | Arreglo |
|---|---|---|
| `502 Bad Gateway` en una URL | Falta el *Add Port* de ese puerto, o el contenedor se cayó | Añadir el puerto; `docker logs infra-<servicio>` |
| mongo-express carga **sin estilos** | El prefijo de `conf/mongo-ui.conf` no coincide con la URL | Volver a lanzar `./up.sh mongo`: lo regenera desde `.env` |
| Las URLs del resumen llevan otro usuario | `infra/.env` tiene un `DEVX_USER` equivocado | Editarlo y relanzar `./up.sh` |
| `ERROR: la clave "X" no esta en images.json` | Falta una imagen | Añadirla a `images.json` |
| La subida da **`503 STORAGE_UNAVAILABLE`** con el stack de infra | El `secret-kv.json` no coincide con las credenciales de MinIO | `grep MINIO conf/secrets-manager-stub/mappings/secret-kv.json`. Para volver a los valores de la plantilla: `./up.sh --regen-secret ibm-secret-manager` |
| He editado `secret-kv.json` y el stub sigue sirviendo lo de antes | WireMock tiene los mappings en memoria: no vigila el disco | `./reload-secret.sh` |
| El stub ya sirve lo nuevo pero la **aplicación** sigue con lo viejo | El secreto se lee una sola vez, al arrancar; no hay refresh | Reiniciar el servicio Spring Boot |
| La app no arranca: `No se pudo leer el secreto ...` | El stub no está levantado, o el 8090 lo ocupa otro compose | `docker ps`; parar el otro stack, o arrancar con `SECRETS_ENABLED=false` |
| El stub arranca y muere solo | Un fichero inválido en `conf/secrets-manager-stub/mappings/` | `docker logs infra-secrets-stub`: WireMock dice qué fichero y por qué |
| **`no space left on device`** al crear el volumen | Se llenó el disco de **Docker**, que en DevX no es el mismo que el `$HOME` que muestra la barra de estado. Oracle necesita ~5 GB ahí | `./down.sh -v` para soltar el volumen a medias, `docker system df` para ver qué ocupa y `docker image prune -a` para tirar imágenes de stacks viejos |
| El pull muere con **`unexpected EOF`** (o un timeout) | El proxy corta la descarga a media capa; la de Oracle son 1,3 GB | Relanzar `./up.sh`: descarga las imágenes con 3 reintentos y reaprovecha las capas ya bajadas, así que cada intento avanza |
| `infra-oracle` tarda en ponerse `healthy` la primera vez | La imagen `faststart` está copiando la base (~3 GB) al volumen `oracle-data` vacío | Esperar: `docker logs -f infra-oracle` hasta `DATABASE IS READY TO USE!` |
| DbGate da **`ORA-01017`** | El volumen `oracle-data` ya tenía datos, así que el entrypoint se saltó el init y no creó `APP_USER` | `./down.sh -v` para empezar limpio |
| DbGate da **`ORA-12514`** (listener no conoce el servicio) | `ORACLE_SERVICE` no coincide con la PDB de la imagen | `FREEPDB1` con `oracle-free`, `XEPDB1` con `oracle-xe` |
| `infra-oracle` muere solo, sin log claro | Oracle Free pide ~1,5-2 GB de RAM y el workspace no da para tanto | Cambiar a `gvenzl/oracle-xe:21-slim-faststart` en `images.json` y `ORACLE_SERVICE=XEPDB1` en `up.sh` |
| `infra-mongo` se queda **`unhealthy`** para siempre | El healthcheck no encuentra `mongosh`, casi seguro porque el registro sirvió otra versión bajo ese tag | `docker inspect infra-mongo --format '{{json .State.Health}}'` y `docker exec infra-mongo mongosh --quiet --eval "db.version()"` |
| mongo-express o la app dan **`Authentication failed`** | El volumen `mongo-data-v8` ya tenía datos, así que el entrypoint se saltó el init y no creó el usuario `admin` | `./down.sh -v` para empezar limpio. A mano: `docker exec -it infra-mongo mongosh` y dentro `use admin` + `db.createUser({user:"admin", pwd:"admin", roles:[{role:"root", db:"admin"}]})` |
| `infra-mongo` sale con **exit 1** nada más arrancar | Solo está definida una de `MONGO_INITDB_ROOT_USERNAME`/`_PASSWORD`: el entrypoint aborta a propósito en vez de arrancar sin autorización | Revisar `services/mongo.yaml`: las dos o ninguna |
