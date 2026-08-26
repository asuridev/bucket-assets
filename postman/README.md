# Colección Postman — ContentMS (HU-211)

| Fichero | Qué es |
|---|---|
| `ContentMS.postman_collection.json` | La colección: flujo principal, errores y salud |
| `ContentMS-local.postman_environment.json` | Entorno apuntando a `localhost:8080` |
| `image1.png` | PNG 1x1 para el flujo principal |
| `nota.txt` | Un `text/plain` para provocar el 422 |

## Puesta en marcha

```bash
# 1. MinIO (crea el bucket solo)
podman-compose -f deploy/docker-compose.yaml up -d

# 2. El servicio
PROFILE=local java -jar target/content-ms-1.0.0.jar
```

`local` es el único perfil de desarrollo y habla con ese MinIO: no hay almacén en memoria,
así que los archivos siguen ahí después de reiniciar.

En Postman: **Import** los dos JSON y selecciona el entorno *ContentMS - local*.

## El único paso manual

Postman no guarda rutas de fichero dentro de la colección, así que en las peticiones
`POST` hay que **seleccionar el archivo a mano** en Body → form-data → campo `file`:

- **POST /v1/save-content** y **400 - jsonString malformado** → `postman/image1.png`
- **422 - tipo de contenido no admitido** → `postman/nota.txt`

Sin ese paso el POST devuelve un 400 por parte de multipart ausente, que no es el
error que la petición pretende demostrar.

Si Postman responde `EISDIR: illegal operation on a directory, read`, es justo esto: el
campo `file` no tiene fichero seleccionado y Postman acaba intentando leer un directorio.
Seleccionalo y desaparece.

Para ahorrarte el paso manual: en **Settings → General → Working directory** apunta a la
carpeta `postman/` de este repo y marca *Allow reading files outside working directory*.
Entonces basta con escribir `image1.png` en el campo `file` y queda guardado en la
colección.

No hace falta declarar el Content-Type del fichero: el servicio lo deduce de la extensión de
`fileName`. La única petición que lo declara es la del 422, porque `.txt` no está entre las
extensiones conocidas y es justo el rechazo que demuestra.

## Orden de ejecución

Las peticiones de **2. Errores** dependen de que el archivo exista, así que lanza antes
**1. Flujo principal**. Con el Collection Runner, el orden de la colección ya es el correcto.

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
