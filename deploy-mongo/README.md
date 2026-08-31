# Stack de MongoDB con UI web

MongoDB (imagen de **Bitnami**) + [mongo-express](https://github.com/mongo-express/mongo-express)
+ un nginx que hace de *shim* para poder servir la UI bajo el subpath de DevX. Es **independiente** del stack de
[`deploy/`](../deploy/) (MinIO, Redis, Redis Commander): red propia, volumen propio, nada
compartido. El servicio ContentMS no lo usa; está aquí para poder trastear con Mongo —por
ejemplo con la colección `OutPutsUrls` que menciona el HU-211— sin tocar nada más.

| Servicio | Contenedor | Puerto publicado |
|---|---|---|
| MongoDB | `contentms-mongo` | `27017` |
| mongo-express | `contentms-mongo-express` | ninguno: solo lo alcanza nginx |
| nginx de Bitnami (shim) | `contentms-mongo-ui-proxy` | `8082` ← la UI se abre por aquí |

## En local

```bash
podman-compose -f deploy-mongo/docker-compose.yaml up -d      # levantar
podman-compose -f deploy-mongo/docker-compose.yaml down       # parar
podman-compose -f deploy-mongo/docker-compose.yaml down -v    # parar y BORRAR los datos
```

UI en [http://localhost:8082](http://localhost:8082), usuario `admin` / `admin`. Mongo lleva
volumen (`mongo-data`), así que los datos sobreviven al `down`; solo `down -v` los borra.

Credenciales por defecto: `mongoadmin` / `mongoadmin`, vía `MONGODB_ROOT_USER` /
`MONGODB_ROOT_PASSWORD` (las de Bitnami, no las `MONGO_INITDB_*` de la imagen oficial).
Comprobación por CLI:

```bash
podman exec contentms-mongo mongosh -u mongoadmin -p mongoadmin --quiet \
  --eval 'db.adminCommand({listDatabases:1}).databases.map(d => d.name)'
```

## En DevX

```bash
cp deploy-mongo/.env.devx.example deploy-mongo/.env.devx   # editar: <usuario> y credenciales
docker compose -f deploy-mongo/docker-compose.yaml --env-file deploy-mongo/.env.devx up -d
```

Luego *Add Port* del **8082** en el panel PORTS y abrir
`https://devx-cardif04.staging.echonet/user/<usuario>/http/8082/`.

**`MONGO_UI_BASE_PATH` aquí va relleno**, justo al revés que el `REDIS_UI_BASE_PATH` de
`deploy/.env.devx`. No es una contradicción: allí la variable configura al propio cliente
(que debe seguir sirviendo en `/`, porque DevX le recorta el prefijo), y aquí configura al
nginx, que reescribe el HTML para que los enlaces **que ve el navegador** lleven el prefijo.

## Sobre la imagen de Bitnami

Va con tag `latest`, que es la única excepción a la regla de "tag fijo" del repo, y no por
gusto: desde agosto de 2025 Bitnami solo publica `latest` en `bitnami/mongodb`. Las versiones
fijas se movieron a `bitnamilegacy/mongodb` y están congeladas (la última es `8.0.13`, sin
parches desde entonces). Si necesitas reproducibilidad, fija el digest:

```bash
podman image inspect bitnami/mongodb:latest --format '{{index .RepoDigests 0}}'
```

y pon ese `bitnami/mongodb@sha256:…` en el compose.

Dos diferencias con la imagen oficial que ya están resueltas en el compose, pero conviene
saberlas si tocas algo: las variables son `MONGODB_ROOT_USER` / `MONGODB_ROOT_PASSWORD`, y los
datos viven en `/bitnami/mongodb`, no en `/data/db`. El contenedor corre como uid 1001 (grupo
root) y ese directorio viene con permisos de grupo, así que el volumen nombrado hereda una
propiedad válida al crearse y no hace falta tocar permisos.

## Por qué hay un nginx en medio

mongo-express pide sus assets con rutas **absolutas** — `/public/css/style.css`, comprobado— y
DevX recorta el prefijo antes de reenviar. Sin reescribir nada, el navegador pediría
`https://devx-…/public/css/style.css`, fuera del prefijo, y la página saldría sin estilos. Es
el caso "cliente con assets absolutos" de [`../DEVX-CLIENTES-WEB.md`](../DEVX-CLIENTES-WEB.md).

[`nginx/default.conf.template`](nginx/default.conf.template) hace cuatro cosas, y las cuatro
hacen falta:

1. `sub_filter` sobre `<base href="/">` y sobre `href="/`, `src="/`, `action="/` → los enlaces
   salen con prefijo. Los `href="https://…"` no casan, así que los enlaces externos se quedan
   como están.
2. `proxy_set_header Accept-Encoding ""` → sin esto la respuesta viene comprimida y
   `sub_filter` no puede tocarla.
3. `proxy_redirect` (con una regla regex) → los `302` de mongo-express salen como
   `Location: /db/foo`, sin prefijo.
4. `absolute_redirect off` → si no, nginx reconstruye ese `Location` como
   `http://<host>/…` y en DevX, que sirve por `https`, se degradaría el esquema.

Con `MONGO_UI_BASE_PATH` vacío las cuatro son no-ops, así que el mismo fichero vale en local
y en DevX.

### La imagen es la de Bitnami, y eso cambia el contrato

Se usa `bitnami/nginx` porque es la que está mirrorada en el registro corporativo, y **no es
intercambiable con la oficial**:

| | Oficial `nginx` | `bitnami/nginx` |
|---|---|---|
| Server blocks | `/etc/nginx/conf.d/*.conf` | `/opt/bitnami/nginx/conf/server_blocks/*.conf` |
| Plantillas + `envsubst` | Automático desde `/etc/nginx/templates` | **No existe** |
| Usuario / puerto | root, 80 | uid 1001, 8080 |

La trampa es la segunda fila: montar el `.template` en `/etc/nginx/templates` con la imagen de
Bitnami **no da ningún error**. Simplemente nadie lo lee, nginx arranca con su configuración
por defecto y el 8082 se queda sin escuchar — desde fuera lo ves como un `502 Bad Gateway`.

Por eso el compose **monta un `.conf` ya resuelto** en `server_blocks/`, y no genera nada al
arrancar:

```
nginx/default.conf.template   ← la plantilla, con ${MONGO_UI_BASE_PATH} sin resolver
nginx/mongo-ui.conf           ← lo que se monta. Commiteado con el prefijo VACÍO (= local)
```

Para DevX se regenera con el prefijo dentro:

```bash
cd deploy-mongo/nginx
sed 's|${MONGO_UI_BASE_PATH}|/user/<usuario>/http/8082|g' default.conf.template > mongo-ui.conf
docker compose -f ../docker-compose.yaml up -d --force-recreate mongo-ui-proxy
```

Y para volver a local, lo mismo sustituyendo por cadena vacía:
`sed 's|${MONGO_UI_BASE_PATH}||g' …`.

Se descartaron las dos alternativas que generan la config sola, y por motivos vividos: el
`command:` en el YAML obliga a escribir el dólar como `$$`, y el `docker compose` de DevX no
respeta esa convención —`envsubst` recibe basura y el contenedor muere al arrancar—; y un
script montado depende de que el fichero exista en esa ruta exacta (si no, Docker monta un
**directorio** vacío y `sh` muere) y de que tenga finales de línea Unix. Un `.conf` ya resuelto
no depende de nada: el contenedor arranca con su entrypoint de siempre.

Lo que se paga: el prefijo queda escrito en el fichero, así que **`mongo-ui.conf` regenerado
aparecerá como modificado en git**. Es lo esperado; no lo commitees con tu prefijo dentro.

Lo que sí trae Bitnami es `ngx_http_sub_module` (verificable con `nginx -V`), que es lo único
que el shim necesita de verdad.

## Si algo falla

| Lo que ves | Causa | Arreglo |
|---|---|---|
| La UI carga pero **sin estilos** | `MONGO_UI_BASE_PATH` no coincide con el prefijo real de la URL (típico: quedó el `<usuario>` de la plantilla) | Corregirlo y `up -d --force-recreate mongo-ui-proxy` |
| `502 Bad Gateway` | DevX perdió el forwarding del 8082 al recrear el contenedor | Quitar y volver a añadir el puerto en PORTS |
| `host not found in upstream "mongo-express"` al arrancar nginx | Se levantó el proxy con la UI parada | `up -d` del stack completo |
| `502 Bad Gateway` y en el log de nginx solo `Welcome to the Bitnami nginx container` | La configuración no se cargó: nginx arrancó con la suya por defecto, en el 8080, y el 8082 se quedó sin escuchar | `docker exec contentms-mongo-ui-proxy ls /opt/bitnami/nginx/conf/server_blocks/` debe listar `mongo-ui.conf`. Si sale vacío o es un directorio, la ruta del volumen no cuadra |
| La UI carga sin estilos | `mongo-ui.conf` tiene un prefijo distinto al de la URL | Regenerarlo con el `sed` y `up -d --force-recreate mongo-ui-proxy` |
| mongo-express no arranca nunca y se queda esperando a Mongo | Un `depends_on` con `condition: service_healthy`: si el healthcheck de Mongo falla en ese entorno, la UI no llega a levantar jamás | Ya no está en el compose, a propósito. mongo-express no necesita Mongo listo: arranca, escucha, y conecta cuando Mongo aparece |
| La UI no lista bases | Credenciales de Mongo distintas entre `mongo` y `mongo-express` | Revisar `MONGO_ROOT_USER` / `MONGO_ROOT_PASSWORD` en el `.env` |
