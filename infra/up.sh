#!/bin/sh
# Genera un docker-compose a la carta y lo levanta. Pensado SOLO para DevX: siempre resuelve
# el prefijo del subpath (/user/<usuario>/http/<puerto>) con el que ese entorno publica cada
# puerto por HTTP.
#
#   ./up.sh                  menu interactivo
#   ./up.sh redis mongo      directo
#   ./up.sh --dry-run all    solo genera el compose, no levanta
#
# Todas las imagenes salen de images.json, sin excepcion.
set -e

cd "$(dirname "$0")"

ENV_FILE=.env
IMAGES=images.json
COMPOSE_FILE=generated/docker-compose.yaml
NGINX_CONF=conf/mongo-ui.conf

die() { echo "ERROR: $*" >&2; exit 1; }

# --- imagenes -------------------------------------------------------------------------
# Parser deliberadamente tonto para un JSON plano: asi no dependemos de jq ni de python,
# que no sabemos si estan en DevX. Aborta si la clave falta: una imagen vacia genera un
# compose invalido con un error incomprensible.
image_of() {
  [ -f "$IMAGES" ] || die "no encuentro $IMAGES"
  value=$(grep -E "\"$1\"[[:space:]]*:" "$IMAGES" | head -1 | sed 's/.*:[[:space:]]*"\(.*\)".*/\1/')
  [ -n "$value" ] || die "la clave \"$1\" no esta en $IMAGES"
  echo "$value"
}

# --- entorno DevX ---------------------------------------------------------------------
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "./$ENV_FILE"
fi

if [ -z "$DEVX_USER" ] || [ -z "$DEVX_HOST" ]; then
  echo "Primera vez: hacen falta los datos del workspace de DevX (se guardan en infra/$ENV_FILE)."
  # cloudide-j31399 -> j31399
  GUESS=$(hostname 2>/dev/null | sed -n 's/.*cloudide-\([A-Za-z0-9]*\).*/\1/p')
  printf "  Usuario de DevX [%s]: " "$GUESS"
  read -r ANSWER || ANSWER=""
  DEVX_USER=${ANSWER:-$GUESS}
  [ -n "$DEVX_USER" ] || die "sin usuario no se pueden construir las URLs"
  printf "  Host de DevX [devx-cardif04.staging.echonet]: "
  read -r ANSWER || ANSWER=""
  DEVX_HOST=${ANSWER:-devx-cardif04.staging.echonet}
  printf 'DEVX_USER=%s\nDEVX_HOST=%s\n' "$DEVX_USER" "$DEVX_HOST" > "$ENV_FILE"
  echo "  Guardado en infra/$ENV_FILE"
  echo
fi

url_for() { echo "https://$DEVX_HOST/user/$DEVX_USER/http/$1/"; }
prefix_for() { echo "/user/$DEVX_USER/http/$1"; }

# --- argumentos -----------------------------------------------------------------------
DRY_RUN=no
SELECTION=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=yes ;;
    redis|mongo|minio) SELECTION="$SELECTION $arg" ;;
    all) SELECTION="redis mongo minio" ;;
    -h|--help) echo "Uso: $0 [--dry-run] [redis] [mongo] [minio] | all"; exit 0 ;;
    *) die "opcion desconocida: $arg" ;;
  esac
done

# --- seleccion interactiva ------------------------------------------------------------
if [ -z "$SELECTION" ]; then
  echo "Que quieres levantar? (cada uno trae su UI)"
  echo "  1) redis   -> Redis Commander en el 8081"
  echo "  2) mongo   -> mongo-express en el 8082 (con su nginx delante)"
  echo "  3) minio   -> consola de MinIO en el 9001"
  printf "Elige (ej: 1,3  o  all): "
  read -r ANSWER || ANSWER=""
  for item in $(echo "$ANSWER" | tr ',' ' '); do
    case "$item" in
      1|redis) SELECTION="$SELECTION redis" ;;
      2|mongo) SELECTION="$SELECTION mongo" ;;
      3|minio) SELECTION="$SELECTION minio" ;;
      all) SELECTION="redis mongo minio" ;;
      "") ;;
      *) die "opcion no valida: $item" ;;
    esac
  done
fi
[ -n "$SELECTION" ] || die "no has seleccionado nada"

has() { echo " $SELECTION " | grep -q " $1 "; }

# --- preguntas dependientes -----------------------------------------------------------
BUCKET=cms-content
if has minio; then
  printf "Nombre del bucket a crear [%s]: " "$BUCKET"
  read -r ANSWER || ANSWER=""
  BUCKET=${ANSWER:-$BUCKET}
fi

# --- imagenes que hacen falta ---------------------------------------------------------
# Se resuelven AQUI, en asignaciones, y no dentro del sed: un `die` dentro de $(...) solo
# mata la subshell y el compose saldria con una imagen vacia. En una asignacion, `set -e`
# corta la ejecucion de verdad.
if has redis; then
  IMG_REDIS=$(image_of redis)
  IMG_REDIS_COMMANDER=$(image_of redis-commander)
fi
if has mongo; then
  IMG_MONGO=$(image_of mongo)
  IMG_MONGO_EXPRESS=$(image_of mongo-express)
  IMG_NGINX=$(image_of nginx)
fi
if has minio; then
  IMG_MINIO=$(image_of minio)
  IMG_MC=$(image_of mc)
fi

# --- ensamblado -----------------------------------------------------------------------
mkdir -p generated
{
  echo "# GENERADO por infra/up.sh. No lo edites a mano: se reescribe en cada ejecucion."
  echo "# Seleccion:$SELECTION   usuario DevX: $DEVX_USER"
  echo "services:"
  echo
} > "$COMPOSE_FILE"

render() {
  sed \
    -e "s|__IMAGE_REDIS__|$IMG_REDIS|g" \
    -e "s|__IMAGE_REDIS_COMMANDER__|$IMG_REDIS_COMMANDER|g" \
    -e "s|__IMAGE_MONGO__|$IMG_MONGO|g" \
    -e "s|__IMAGE_MONGO_EXPRESS__|$IMG_MONGO_EXPRESS|g" \
    -e "s|__IMAGE_NGINX__|$IMG_NGINX|g" \
    -e "s|__IMAGE_MINIO__|$IMG_MINIO|g" \
    -e "s|__IMAGE_MC__|$IMG_MC|g" \
    -e "s|__MINIO_PUBLIC_URL__|$(url_for 9001)|g" \
    -e "s|__BUCKET__|$BUCKET|g" \
    "services/$1" >> "$COMPOSE_FILE"
  echo >> "$COMPOSE_FILE"
}

VOLUMES=""
if has redis; then
  render redis.yaml
  render redis-commander.yaml
fi
if has mongo; then
  render mongo.yaml
  # Justo detras de mongo.yaml y ANTES de las UIs: mongo-init crea el usuario admin, y
  # mongo-express no puede autenticar hasta que exista.
  render mongo-init.yaml
  render mongo-express.yaml
  render mongo-ui-proxy.yaml
  VOLUMES="$VOLUMES mongo-data"
fi
if has minio; then
  render minio.yaml
  render minio-init.yaml
  VOLUMES="$VOLUMES minio-data"
fi

if [ -n "$VOLUMES" ]; then
  echo "volumes:" >> "$COMPOSE_FILE"
  for volume in $VOLUMES; do
    echo "  $volume:" >> "$COMPOSE_FILE"
  done
fi
echo "Compose generado en infra/$COMPOSE_FILE"

# --- config del shim de nginx ---------------------------------------------------------
if has mongo; then
  PREFIX=$(prefix_for 8082)
  # Comillas dobles con \$ escapado: el patron llega literal a sed y solo se expande
  # $PREFIX. Con comillas simples no se expandiria el prefijo; sin escapar, la shell
  # vaciaria el patron antes de que sed lo viera. Es el error clasico al hacerlo a mano.
  sed "s|\${MONGO_UI_BASE_PATH}|$PREFIX|g" conf/default.conf.template > "$NGINX_CONF"
  COUNT=$(grep -c -- "$PREFIX" "$NGINX_CONF" || true)
  [ "$COUNT" -gt 0 ] || die "la sustitucion del prefijo en $NGINX_CONF no hizo nada"
  echo "Config de nginx generada con prefijo $PREFIX ($COUNT lineas)"
fi

if [ "$DRY_RUN" = yes ]; then
  echo
  echo "--dry-run: no se levanta nada. Revisa infra/$COMPOSE_FILE"
  exit 0
fi

# --- arranque -------------------------------------------------------------------------
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v podman-compose >/dev/null 2>&1; then
  COMPOSE="podman-compose"
else
  die "no encuentro ni 'docker compose' ni 'podman-compose'"
fi

echo
# -p infra: sin esto el proyecto tomaria el nombre del directorio (generated/) y los
# volumenes se llamarian generated_mongo-data. Con nombre fijo, down.sh encuentra siempre
# lo que up.sh creo.
$COMPOSE -p infra -f "$COMPOSE_FILE" up -d

# --- mongo-init -----------------------------------------------------------------------
# mongo-init es de un solo uso y `up -d` no espera a que acabe. Si falla, el resumen de
# abajo estaria anunciando una URI (admin:admin@...) que no autentica, y el siguiente en
# enterarse seria quien arranque el servicio. Asi que se comprueba aqui.
if has mongo; then
  ENGINE=docker
  [ "$COMPOSE" = "podman-compose" ] && ENGINE=podman

  echo
  printf 'Esperando a mongo-init (es quien crea el usuario admin de MongoDB)'
  STATE=""
  for intento in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    STATE=$($ENGINE inspect -f '{{.State.Status}}:{{.State.ExitCode}}' infra-mongo-init 2>/dev/null || echo "")
    case "$STATE" in
      exited:0) break ;;
      exited:*) break ;;
      *) printf '.'; sleep 2 ;;
    esac
  done
  echo

  case "$STATE" in
    exited:0)
      echo "  mongo-init OK: el usuario admin de MongoDB existe."
      ;;
    exited:*)
      echo "  ERROR: mongo-init termino en fallo ($STATE)." >&2
      echo "  Sin el usuario admin, la URI de MongoDB de aqui abajo NO autentica." >&2
      echo "  Mira que paso:  $ENGINE logs infra-mongo-init" >&2
      ;;
    *)
      echo "  AVISO: mongo-init sigue sin terminar (estado: '${STATE:-desconocido}')." >&2
      echo "  Comprueba con:  $ENGINE logs infra-mongo-init" >&2
      ;;
  esac
fi

# --- resumen --------------------------------------------------------------------------
echo
echo "============================================================================"
echo " STACK LEVANTADO   (usuario DevX: $DEVX_USER)"
echo "============================================================================"
echo
echo " UIs -- recuerda hacer \"Add Port\" en el panel PORTS de la IDE"
has redis && printf '   Redis Commander  %-56s admin / admin\n' "$(url_for 8081)"
has mongo && printf '   mongo-express    %-56s admin / admin\n' "$(url_for 8082)"
has minio && printf '   MinIO consola    %-56s admin / adminadmin\n' "$(url_for 9001)"
echo
echo " Conexiones desde OTRO CONTENEDOR de este compose (por nombre de servicio)"
has redis && echo "   Redis    redis:6379                                   sin auth"
has mongo && echo "   MongoDB  mongodb://admin:admin@mongo:27017/?authSource=admin"
has minio && echo "   MinIO    http://minio:9000   admin / adminadmin   bucket: $BUCKET"
echo
echo " Conexiones desde el propio workspace (puertos publicados)"
has redis && echo "   Redis    localhost:6379"
has mongo && echo "   MongoDB  mongodb://admin:admin@localhost:27017/?authSource=admin"
has minio && echo "   MinIO    http://localhost:9000"
echo
echo " MinIO es la unica excepcion a admin/admin: rechaza contrasenas de menos de 8"
echo " caracteres, por eso es adminadmin."
has mongo && echo
has mongo && echo " El admin/admin de MongoDB lo crea el contenedor mongo-init, no la imagen: Bitnami"
has mongo && echo " solo lo creara sobre un volumen vacio, y sobre uno ya existente la base admin se"
has mongo && echo " quedaria sin ningun usuario."
echo
echo " Para bajarlo:  ./down.sh        (los datos sobreviven)"
echo "                ./down.sh -v     (borra tambien los volumenes)"
echo "============================================================================"
