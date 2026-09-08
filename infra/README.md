# `infra/` — stack a la carta para DevX

Un solo comando que pregunta qué necesitas, arma el `docker-compose.yaml` con **solo eso**, lo
levanta y termina imprimiendo credenciales y URLs.

```bash
./up.sh                  # menú interactivo
./up.sh redis mongo      # directo
./up.sh --dry-run all    # solo genera el compose, para revisarlo
./down.sh                # baja el stack (los datos sobreviven)
./down.sh -v             # baja y borra los volúmenes (pide confirmación)
```

Cada servicio arrastra su UI automáticamente: **redis** → Redis Commander, **mongo** →
mongo-express (con su nginx delante), **minio** → creación del bucket, que se pregunta al vuelo.

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
| MinIO API | 9000 | `admin` / `adminadmin` |
| MinIO consola (UI) | 9001 | `admin` / `adminadmin` |

**MinIO es la única excepción a `admin`/`admin`**: rechaza arrancar con una contraseña de menos
de 8 caracteres.

**El `admin`/`admin` de MongoDB lo crea la propia imagen**, con
`MONGO_INITDB_ROOT_USERNAME`/`MONGO_INITDB_ROOT_PASSWORD`: el entrypoint oficial activa `--auth`
por su cuenta y crea ese usuario con rol `root`. Pero **solo en la primera inicialización, con el
directorio de datos vacío** — se salta el init si ya encuentra `/data/db/WiredTiger`. Sobre un
volumen con datos previos la base `admin` se quedaría sin ningún usuario y la URI
`mongodb://admin:admin@mongo:27017/?authSource=admin` no autenticaría; el remedio es empezar
limpio con `./down.sh -v`.

Tras levantar, hay que hacer ***Add Port*** en el panel PORTS de la IDE con cada puerto de UI
(8081, 8082, 9001). Sin eso no hay forwarded address y la URL da 502.

## Qué hay en el directorio

```
images.json               TODAS las imágenes. Es el único sitio donde se cambia una versión.
.env                      DEVX_USER / DEVX_HOST. Lo crea up.sh la primera vez. No se commitea.
services/*.yaml           Un fragmento de compose por servicio, con marcadores __IMAGE_X__.
conf/default.conf.template  Plantilla del nginx que sirve mongo-express bajo el subpath.
conf/mongo-ui.conf        Generada por up.sh con tu prefijo dentro. No se commitea.
generated/docker-compose.yaml  Lo que up.sh arma y levanta. No se commitea.
```

`up.sh` resuelve **antes de ensamblar** las imágenes que necesita y aborta si falta alguna
clave en `images.json`. Es a propósito: una imagen vacía produce un compose inválido con un
error indescifrable.

Cada fragmento nuevo hay que registrarlo en el bloque de ensamblado de `up.sh`. El de Mongo son
tres `render`, y el orden importa porque es el que acaba en el compose:

```sh
render mongo.yaml
render mongo-express.yaml
render mongo-ui-proxy.yaml
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
- **MinIO** necesita `MINIO_BROWSER_REDIRECT_URL` con la URL pública completa, o la consola
  redirige a `localhost`.

## Si algo falla

| Síntoma | Causa | Arreglo |
|---|---|---|
| `502 Bad Gateway` en una URL | Falta el *Add Port* de ese puerto, o el contenedor se cayó | Añadir el puerto; `docker logs infra-<servicio>` |
| mongo-express carga **sin estilos** | El prefijo de `conf/mongo-ui.conf` no coincide con la URL | Volver a lanzar `./up.sh mongo`: lo regenera desde `.env` |
| Las URLs del resumen llevan otro usuario | `infra/.env` tiene un `DEVX_USER` equivocado | Editarlo y relanzar `./up.sh` |
| `ERROR: la clave "X" no esta en images.json` | Falta una imagen | Añadirla a `images.json` |
| `infra-mongo` se queda **`unhealthy`** para siempre | El healthcheck no encuentra `mongosh`, casi seguro porque el registro sirvió otra versión bajo ese tag | `docker inspect infra-mongo --format '{{json .State.Health}}'` y `docker exec infra-mongo mongosh --quiet --eval "db.version()"` |
| mongo-express o la app dan **`Authentication failed`** | El volumen `mongo-data-v8` ya tenía datos, así que el entrypoint se saltó el init y no creó el usuario `admin` | `./down.sh -v` para empezar limpio. A mano: `docker exec -it infra-mongo mongosh` y dentro `use admin` + `db.createUser({user:"admin", pwd:"admin", roles:[{role:"root", db:"admin"}]})` |
| `infra-mongo` sale con **exit 1** nada más arrancar | Solo está definida una de `MONGO_INITDB_ROOT_USERNAME`/`_PASSWORD`: el entrypoint aborta a propósito en vez de arrancar sin autorización | Revisar `services/mongo.yaml`: las dos o ninguna |
