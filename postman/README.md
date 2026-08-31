# Colección Postman — ContentMS (HU-211)

| Fichero | Qué es |
|---|---|
| `ContentMS.postman_collection.json` | La colección: flujo principal, errores, salud y caché |
| `ContentMS-local.postman_environment.json` | Entorno apuntando a `localhost:8080` |
| `image1.png` | PNG 1x1 para el flujo principal |
| `nota.txt` | Un `text/plain` para provocar el 422 |

## Puesta en marcha

```bash
# 1. MinIO (crea el bucket solo) + Redis (la cache del GET)
podman-compose -f deploy/docker-compose.yaml up -d

# 2. El servicio
PROFILE=local java -jar target/content-ms-1.0.0.jar
```

`local` es el único perfil de desarrollo y habla con ese MinIO: no hay almacén en memoria,
así que los archivos siguen ahí después de reiniciar.

En Postman: **Import** los dos JSON y selecciona el entorno *ContentMS - local*.

## Los ficheros de los POST

Las peticiones con `file` ya traen la ruta guardada (`image1.png` / `nota.txt`), relativa a
esta carpeta. En Postman, para que las encuentre: **Settings → General → Working directory**
apuntando a `postman/` de este repo.

Si aun así responde `EISDIR: illegal operation on a directory, read`, es que el campo `file`
se quedó sin fichero: vuelve a seleccionarlo en Body → form-data.

## Desde la línea de comandos

```bash
npx newman run postman/ContentMS.postman_collection.json \
  -e postman/ContentMS-local.postman_environment.json \
  --working-dir postman
```

`--working-dir` es lo que resuelve las rutas de los ficheros. La colección entera debe pasar
en verde con MinIO y Redis arriba.

No hace falta declarar el Content-Type del fichero: el servicio lo deduce de la extensión de
`fileName`. La única petición que lo declara es la del 422, porque `.txt` no está entre las
extensiones conocidas y es justo el rechazo que demuestra.

## Orden de ejecución

Las peticiones de **2. Errores** y **4. Cache** dependen de que el archivo exista, así que
lanza antes **1. Flujo principal**. Con el Collection Runner, el orden de la colección ya es
el correcto.

### La carpeta *4. Cache*

Sus dos peticiones sólo comprueban que la segunda respuesta es idéntica a la primera. Que la
segunda **no tocó el bucket** se demuestra apagando MinIO entre ambas: sin caché eso sería un
`503 STORAGE_UNAVAILABLE`.

```bash
podman stop contentms-minio
npx newman run postman/ContentMS.postman_collection.json \
  -e postman/ContentMS-local.postman_environment.json \
  --working-dir postman --folder "4. Cache"
podman start contentms-minio
```

Y para ver la entrada en Redis:

```bash
podman exec contentms-redis redis-cli KEYS 'contentms:*'
podman exec contentms-redis redis-cli TTL 'contentms:content::cmsContent/12345/image1.png'
```

`correlation_id` y `request_id` se generan en cada envío con `{{$guid}}`; no hay que tocarlos.
Sólo van en las peticiones `POST`: el `GET /v1/content-loaded` no lleva ninguna cabecera.

## Variables

| Variable | Para qué |
|---|---|
| `baseUrl` | Dónde escucha el servicio |
| `partnerId` | El socio: determina el prefijo `partnerId/` dentro del bucket |
| `fileName` | Nombre con el que se guarda y se recupera |

En el POST el socio viaja en el `jsonString` y en el header `_p`; en el GET va dentro del
propio `context_url` (`{{partnerId}}/{{fileName}}`). Cambiar `partnerId` y repetir el GET
sólo demuestra que esa clave no existe: el GET no comprueba quién pregunta, así que no hay
aislamiento entre socios mientras no se cablee la autorización.
