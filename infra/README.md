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

**El `admin`/`admin` de MongoDB lo crea `mongo-init`, no la imagen.** Bitnami solo aplica
`MONGODB_ROOT_USER`/`MONGODB_ROOT_PASSWORD` en la primera inicialización, con el directorio de
datos vacío: sobre un volumen `mongo-data` que ya existía, la base `admin` se queda **sin ningún
usuario** y la URI `mongodb://admin:admin@mongo:27017/?authSource=admin` no autentica. El servicio
`mongo-init` lo garantiza en cada `up.sh`, exista ya el volumen o no.

Tras levantar, hay que hacer ***Add Port*** en el panel PORTS de la IDE con cada puerto de UI
(8081, 8082, 9001). Sin eso no hay forwarded address y la URL da 502.

## Qué hay en el directorio

```
images.json               TODAS las imágenes. Es el único sitio donde se cambia una versión.
.env                      DEVX_USER / DEVX_HOST. Lo crea up.sh la primera vez. No se commitea.
services/*.yaml           Un fragmento de compose por servicio, con marcadores __IMAGE_X__.
services/mongo-init.yaml  Init de un solo uso: crea el usuario admin de Mongo. Ver abajo.
conf/default.conf.template  Plantilla del nginx que sirve mongo-express bajo el subpath.
conf/mongo-ui.conf        Generada por up.sh con tu prefijo dentro. No se commitea.
generated/docker-compose.yaml  Lo que up.sh arma y levanta. No se commitea.
```

`up.sh` resuelve **antes de ensamblar** las imágenes que necesita y aborta si falta alguna
clave en `images.json`. Es a propósito: una imagen vacía produce un compose inválido con un
error indescifrable.

Cada fragmento nuevo hay que registrarlo en el bloque de ensamblado de `up.sh`. El de Mongo son
cuatro `render`, y el orden importa porque es el que acaba en el compose:

```sh
render mongo.yaml
render mongo-init.yaml
render mongo-express.yaml
render mongo-ui-proxy.yaml
```

## La versión de MongoDB no está fijada, y eso se nota

`images.json` pide `bitnami/mongodb:latest` porque Bitnami ya no publica tags versionados
públicos. Eso significa que **la versión del servidor depende del registro que responda**: en DevX
el mirror corporativo resuelve ese `latest` a **MongoDB 4.4.26**, mientras que contra Docker Hub
sale una 8.x. Comprobar siempre con qué se está trabajando:

```bash
docker exec infra-mongo mongo --quiet --eval "db.version()"     # 4.4 y anteriores
docker exec infra-mongo mongosh --quiet --eval "db.version()"   # 5.0 en adelante
```

La diferencia que muerde es la shell: **4.4 no trae `mongosh`, solo el `mongo` legacy**. Por eso
tanto el healthcheck de `mongo.yaml` como el init prueban las dos, y en las dos ubicaciones
(`/opt/bitnami/mongodb/bin/` y el `PATH`). Cualquier comando nuevo que se añada aquí tiene que
hacer lo mismo, o funcionará en una máquina y no en la otra.

Y no basta con elegir bien el binario: **las dos shells no devuelven lo mismo**. `db.auth()` da `1`
en el `mongo` de 4.4 y `{ ok: 1 }` en `mongosh`, así que cualquier script que compare el resultado
tiene que aceptar las dos formas. `mongo-init` ya lo hace, y está comentado en el fragmento.

## Dos cosas que `mongo-init` da por sentadas (y por qué)

**`condition: service_healthy` no siempre se respeta.** `docker compose` lo cumple; **podman-compose
lo ignora** y arranca `mongo-init` a la vez que `mongod`. Por eso el init no confía en el
`depends_on`: reintenta hasta 30 veces cada 4 s, y si se agotan sale con código distinto de 0 en
lugar de fingir que fue bien.

**Lo que se reintenta es el trabajo, no un ping previo.** Bitnami levanta un `mongod` **temporal**
para inicializar y luego lo reinicia. Una primera versión esperaba con un ping y después lanzaba el
script una sola vez: el ping pasaba contra el `mongod` temporal y, cuando le tocaba al script,
`mongod` estaba reiniciándose y la conexión moría. Reintentando el script entero —que es
idempotente— esa ventana deja de importar. Se ve en los logs de un arranque en frío: un primer
intento fallido y el segundo en verde.

Para reproducir en local la versión de DevX sin depender del mirror, la imagen oficial sí tiene
tag fijo:

```bash
podman run -d --name t-mongo docker.io/library/mongo:4.4.26 --auth
```

Arrancada así queda en el mismo estado que el Mongo de DevX: autorización activada y **cero
usuarios**, que es justo el caso que cubre `mongo-init`.

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
| `infra-mongo` se queda **`unhealthy`** para siempre | El healthcheck usa una shell que esa imagen no tiene (4.4 no trae `mongosh`) | Ya contemplado: el test prueba `mongosh` y `mongo`. Si vuelve a pasar, mirar `docker inspect infra-mongo --format '{{json .State.Health}}'` |
| mongo-express o la app dan **`Authentication failed`** | `mongo-init` no llegó a crear el usuario `admin` | `docker logs infra-mongo-init`. Salida de emergencia, a mano: `docker exec -it infra-mongo mongo` y dentro `use admin` + `db.createUser({user:"admin", pwd:"admin", roles:[{role:"root", db:"admin"}]})` |
| `infra-mongo-init` termina con código distinto de 0 | Suele ser un `admin` que ya existe con OTRA contraseña: el init no la pisa a propósito | `docker logs infra-mongo-init` para ver el error, y o se usa esa contraseña o se empieza limpio con `./down.sh -v` |
| `infra-mongo-init` escribe `Error: Authentication failed.` o un `ERROR ... requires authentication` en el primer intento | **Es normal**: el init comprueba si `admin` existe intentando autenticar (la shell legacy de 4.4 imprime eso antes de devolver el control), y en un arranque en frío el primer intento puede pillar a `mongod` reiniciándose | Nada. Lo que importa es la última línea (`usuario admin creado...` o `ya existe`) y que el contenedor acabe en `Exited (0)` |
