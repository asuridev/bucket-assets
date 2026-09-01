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
