# Exponer clientes web (Redis, BBDD…) en el compose bajo DevX

Guía sacada de montar Redis Commander sobre el `deploy/docker-compose.yaml` de este repo, con
todo comprobado en `devx-cardif04`. Sirve para el siguiente cliente que tengas que añadir:
pgAdmin, Adminer, mongo-express, Kafka UI, lo que sea.

---

## 1. Qué te impone DevX

| Restricción | Consecuencia |
|---|---|
| Solo se expone **HTTP** | El puerto nativo de la base de datos (6379, 5432, 27017…) no te sirve de nada desde fuera. Necesitas una UI web dentro del compose |
| La URL pública es un **subpath** de un host compartido: `https://devx-cardif04.staging.echonet/user/<usuario>/http/<puerto>/` | No hay subdominio por servicio: todo cuelga de la misma raíz |
| El proxy **recorta el prefijo** | Al contenedor le llega `/bootstrap/css/…`, no `/user/<usuario>/http/8081/bootstrap/css/…` |
| Los puertos hay que declararlos a mano | *Add Port* en el panel **PORTS** de la IDE, o no hay forwarded address |
| No hay `docker exec` cómodo desde fuera, ni acceso al puerto de la BD | La UI web es la **única** vía de inspección |

Que el proxy recorta el prefijo está verificado, no inferido: con `URL_PREFIX` vacío, el
navegador pide `…/user/j31399/http/8081/bootstrap/css/bootstrap.css` y el contenedor responde
`304`. Solo puede pasar si el proxy le entregó la ruta sin el prefijo.

---

## 2. La regla de oro

> **El cliente tiene que servir en la raíz (`/`) y pedir sus assets con rutas relativas.**

Las dos mitades importan:

- **Servir en la raíz** → cualquier variable de *base path* / *context path* del cliente va
  **vacía**, sin valor, ni siquiera `/`. Es justo lo contrario de lo que pediría un Nginx que
  preserva la ruta. Si le pones el prefijo, el cliente escucha en una ruta a la que nunca le
  llega nada, y responde con su 404/401 propio.
- **Assets relativos** → como la página vive bajo el prefijo pero el contenedor se cree en la
  raíz, un enlace `bootstrap/css/x.css` se resuelve contra `…/http/8081/` y acierta; uno
  absoluto `/bootstrap/css/x.css` se va a la raíz del host, fuera de tu prefijo, y da 404. La
  página carga a medias, sin estilos o en blanco.

Esto **descarta clientes**, y es lo que hay que mirar antes de elegir. Por ejemplo,
RedisInsight se descartó aquí porque su propia documentación dice que servir bajo un prefijo
(*path-rewriting*) no está soportado.

---

## 3. Cómo validar un candidato en tres pasos

Levanta el contenedor en local y, antes de llevarlo a DevX:

```bash
# 1. ¿sirve en la raíz?
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:<puerto>/     # 200

# 2. ¿sus assets son relativos? Ninguno debe empezar por "/"
curl -s http://localhost:<puerto>/ | grep -o -E '(src|href)="[^"]*"' | sort -u
```

Ejemplo real, redis-commander: `href="bootstrap/css/bootstrap.css"`, `href="./"`,
`src="clipboard/clipboard.min.js"`. Ninguno con barra inicial: apto.

```bash
# 3. ya en DevX: Add Port, abrir la forwarded address y mirar DevTools -> Network
```

Si algún recurso se pide a `https://devx-…/algo` **sin** tu `/user/<usuario>/http/<puerto>/`
delante y da 404, ese cliente no cumple la regla. Lo primero es buscar otro; si no lo hay —le
pasó a mongo-express, que es el cliente estándar de Mongo— se le pone un nginx delante que
reescriba la respuesta. El patrón montado y probado está en
[`deploy-mongo/`](deploy-mongo/README.md) y se resume en la sección 6.

---

## 4. Plantilla de servicio

```yaml
  <nombre>-ui:
    image: <imagen>:<tag fijo>        # tag fijo, nunca `latest`
    container_name: contentms-<nombre>-ui
    depends_on:
      <servicio-bbdd>:
        condition: service_healthy
    environment:
      # La UI habla con la BBDD por el NOMBRE DEL SERVICIO en la red interna del compose,
      # nunca por localhost ni por el puerto publicado.
      <VAR_HOST>: <servicio-bbdd>
      # Base path: vacio en DevX. Se deja parametrizado por si otro entorno reenvia el prefijo.
      <VAR_BASE_PATH>: ${<NOMBRE>_UI_BASE_PATH:-}
      # Auth propia OBLIGATORIA: la URL de DevX es alcanzable por terceros.
      <VAR_USER>: ${<NOMBRE>_UI_USER:-admin}
      <VAR_PASSWORD>: ${<NOMBRE>_UI_PASSWORD:-admin}
    ports:
      - "<puerto>:<puerto>"           # LITERAL, ver seccion 6
```

Y el `.env` del entorno (plantilla en `deploy/.env.devx.example`, el real va en `.gitignore`
porque lleva credenciales):

```dotenv
<NOMBRE>_UI_BASE_PATH=
<NOMBRE>_UI_USER=...
<NOMBRE>_UI_PASSWORD=...
```

```bash
docker compose -f deploy/docker-compose.yaml --env-file deploy/.env.devx up -d
```

En DevX el binario es `docker compose`; en local, en Windows, `podman-compose` (ver README §4).

---

## 5. Dónde vive el *base path* de cada cliente

La variable que hay que dejar **vacía**:

| Cliente | Variable |
|---|---|
| redis-commander | `URL_PREFIX` |
| Adminer | no tiene: ya es relativo |
| pgAdmin | `SCRIPT_NAME` |
| mongo-express | `ME_CONFIG_SITE_BASEURL`, **pero no sirve para esto**: el `baseHref` de sus plantillas se deriva de la petición y el router se monta siempre en `/`. Necesita el shim de la sección 6 |
| phpMyAdmin | `PMA_ABSOLUTE_URI` |
| Kafka UI y cualquier Spring Boot | `SERVER_SERVLET_CONTEXT_PATH` |
| Grafana | `GF_SERVER_ROOT_URL` + `serve_from_sub_path` |
| RedisInsight | `RI_PROXY_PATH` — pero su doc dice que el path-rewriting no está soportado; evitar |

Antes de fiarte de la tabla, aplica la sección 3: la variable te la dice la documentación, pero
si los assets son absolutos da igual lo que configures.

---

## 6. Errores que te vas a encontrar

| Síntoma | Causa | Arreglo |
|---|---|---|
| `ValueError: invalid literal for int() with base 10: '-6379}'` al hacer `up` | El `docker compose` de DevX **no sustituye variables dentro de `ports:`**: parte `${REDIS_PORT:-6379}:6379` por `:` e intenta leer `-6379}` como puerto | Puertos **literales** en `ports:`. En `environment:` la sustitución sí funciona |
| El cliente responde su propio 401/404 en la URL correcta | El base path tiene valor y DevX lo recorta: el cliente escucha en una ruta que nunca recibe | Vaciar la variable y recrear el contenedor |
| `502 Bad Gateway` | El proxy no alcanza el puerto. Casi siempre DevX perdió el forwarding al recrear el contenedor | Quitar y volver a añadir el puerto en **PORTS** |
| Página en blanco o sin estilos, 404 de assets contra la raíz del host | El cliente usa rutas absolutas | Cambiar de cliente (sección 3) o, si no hay alternativa, montar el shim de abajo |
| `unsupported media type application/vnd.cncf.notary.signature` al hacer pull | El registro por el que pasa DevX devuelve un índice con firmas Notary que el cliente no sabe saltarse | Fijar la imagen por digest del manifiesto de la plataforma (`imagen:tag@sha256:…`), o usar la ruta explícita del registro corporativo |

---

### El shim de nginx, cuando el cliente usa rutas absolutas

Traduce entre "el navegador vive bajo un prefijo" y "el contenedor cree estar en la raíz".
Implementación completa y probada en
[`deploy-mongo/nginx/default.conf.template`](deploy-mongo/nginx/default.conf.template); las
cuatro piezas, todas necesarias:

| Pieza | Para qué |
|---|---|
| `sub_filter` sobre `<base href="/">` y sobre `href="/`, `src="/`, `action="/` | Mete el prefijo en los enlaces del HTML. `<base>` por sí solo no basta: no afecta a URLs absolutas |
| `proxy_set_header Accept-Encoding ""` | Sin esto la respuesta llega comprimida y `sub_filter` no puede tocarla |
| `proxy_redirect`, incluida una regla con regex | Los `Location:` de los 302 salen sin prefijo |
| `absolute_redirect off` | Si no, nginx reconstruye ese `Location` como `http://<host>/…` y degrada el esquema, que en DevX es `https` |

El cliente se queda **sin `ports:`** y solo lo alcanza el nginx por la red interna: una sola
puerta de entrada. Con la variable del prefijo vacía todas las reescrituras son no-ops, así
que el mismo fichero sirve en local y en DevX.

## 7. Checklist

- [ ] Tag de imagen fijo, nunca `latest`.
- [ ] Puertos literales en `ports:`, sin `${VAR:-default}`.
- [ ] La UI apunta a la BBDD por nombre de servicio, no por `localhost`.
- [ ] El puerto nativo de la BBDD **no** se publica hacia DevX; solo el de la UI.
- [ ] Variable de base path parametrizada y **vacía** en `.env.devx`.
- [ ] Credenciales de la UI cambiadas respecto al default, y el `.env` real en `.gitignore`.
- [ ] *Add Port* hecho en el panel **PORTS**.
- [ ] Assets verificados como relativos (sección 3), o shim de nginx montado si no lo son.
