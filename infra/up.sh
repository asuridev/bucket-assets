#!/bin/sh
# Genera un docker-compose a la carta y lo levanta. Pensado SOLO para DevX: siempre resuelve
# el prefijo del subpath (/user/<usuario>/http/<puerto>) con el que ese entorno publica cada
# puerto por HTTP.
#
#   ./up.sh                          menu interactivo
#   ./up.sh redis mongo              directo
#   ./up.sh ibm-secret-manager       solo la emulacion de Secrets Manager
#   ./up.sh --dry-run all            solo genera el compose, no levanta
#   ./up.sh --regen-secret ibm-secret-manager   rehace el secreto del stub desde la plantilla
#
# Todas las imagenes salen de images.json, sin excepcion.
set -e

cd "$(dirname "$0")"

ENV_FILE=.env
IMAGES=images.json
COMPOSE_FILE=generated/docker-compose.yaml
NGINX_CONF=conf/mongo-ui.conf
STUB_DIR=conf/secrets-manager-stub
STUB_TEMPLATE=$STUB_DIR/secret-kv.json.template
STUB_SECRET=$STUB_DIR/mappings/secret-kv.json

# Credenciales de MinIO, en UN solo sitio. up.sh las sustituye en services/minio.yaml,
# services/minio-init.yaml y en la plantilla del secreto que consume el stub de Secrets
# Manager. Escritas a mano en los tres, cambiar una sola dejaria al servicio firmando con
# unas credenciales y a MinIO esperando otras: un 503 al subir, y sin mas pista.
MINIO_USER=admin
MINIO_PASSWORD=adminadmin   # MinIO rechaza contrasenas de menos de 8 caracteres

# Credenciales de Oracle, tambien en UN solo sitio: up.sh las sustituye en services/oracle.yaml
# (donde crean el usuario) y en services/oracle-ui.yaml (donde la UI se conecta con ellas).
# ORACLE_SERVICE es la PDB de la imagen: FREEPDB1 en oracle-free, XEPDB1 en oracle-xe.
ORACLE_USER=admin
ORACLE_PASSWORD=admin
ORACLE_SERVICE=FREEPDB1

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
REGEN_SECRET=no
SELECTION=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=yes ;;
    --regen-secret) REGEN_SECRET=yes ;;
    redis|mongo|minio|oracle|ibm-secret-manager) SELECTION="$SELECTION $arg" ;;
    all) SELECTION="redis mongo minio oracle ibm-secret-manager" ;;
    -h|--help) echo "Uso: $0 [--dry-run] [--regen-secret] [redis] [mongo] [minio] [oracle] [ibm-secret-manager] | all"; exit 0 ;;
    *) die "opcion desconocida: $arg" ;;
  esac
done

# --- seleccion interactiva ------------------------------------------------------------
if [ -z "$SELECTION" ]; then
  echo "Que quieres levantar? (cada uno trae su UI)"
  echo "  1) redis   -> Redis Commander en el 8081"
  echo "  2) mongo   -> mongo-express en el 8082 (con su nginx delante)"
  echo "  3) minio   -> consola de MinIO en el 9001"
  echo "  4) oracle  -> DbGate en el 8083"
  echo "  5) ibm-secret-manager -> emulacion de Secrets Manager en el 8090 (no es una UI)"
  printf "Elige (ej: 1,3  o  all): "
  read -r ANSWER || ANSWER=""
  for item in $(echo "$ANSWER" | tr ',' ' '); do
    case "$item" in
      1|redis) SELECTION="$SELECTION redis" ;;
      2|mongo) SELECTION="$SELECTION mongo" ;;
      3|minio) SELECTION="$SELECTION minio" ;;
      4|oracle) SELECTION="$SELECTION oracle" ;;
      5|ibm-secret-manager) SELECTION="$SELECTION ibm-secret-manager" ;;
      all) SELECTION="redis mongo minio oracle ibm-secret-manager" ;;
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
if has oracle; then
  IMG_ORACLE=$(image_of oracle)
  IMG_DBGATE=$(image_of dbgate)
fi
if has ibm-secret-manager; then
  IMG_WIREMOCK=$(image_of wiremock)
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
    -e "s|__IMAGE_WIREMOCK__|$IMG_WIREMOCK|g" \
    -e "s|__MINIO_PUBLIC_URL__|$(url_for 9001)|g" \
    -e "s|__MINIO_USER__|$MINIO_USER|g" \
    -e "s|__MINIO_PASSWORD__|$MINIO_PASSWORD|g" \
    -e "s|__BUCKET__|$BUCKET|g" \
    -e "s|__IMAGE_ORACLE__|$IMG_ORACLE|g" \
    -e "s|__IMAGE_DBGATE__|$IMG_DBGATE|g" \
    -e "s|__ORACLE_USER__|$ORACLE_USER|g" \
    -e "s|__ORACLE_PASSWORD__|$ORACLE_PASSWORD|g" \
    -e "s|__ORACLE_SERVICE__|$ORACLE_SERVICE|g" \
    "services/$1" >> "$COMPOSE_FILE"
  echo >> "$COMPOSE_FILE"
}

VOLUMES=""
if has redis; then
  render redis.yaml
  render redis-commander.yaml
fi
if has mongo; then
  # El orden importa: es el que acaba en el compose, y cada UI va detras de lo que sirve.
  render mongo.yaml
  render mongo-express.yaml
  render mongo-ui-proxy.yaml
  VOLUMES="$VOLUMES mongo-data-v8"
fi
if has minio; then
  render minio.yaml
  render minio-init.yaml
  VOLUMES="$VOLUMES minio-data"
fi
if has oracle; then
  # La UI detras de lo que sirve, igual que en mongo. DbGate no lleva volumen: sus conexiones
  # salen de las variables de entorno, no de su almacen interno.
  render oracle.yaml
  render oracle-ui.yaml
  VOLUMES="$VOLUMES oracle-data"
fi
if has ibm-secret-manager; then
  # Sin volumen a proposito: un stub no tiene estado que preservar.
  render secrets-manager-stub.yaml
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

# --- secreto que sirve el stub de Secrets Manager -----------------------------------
# Se genera, no se commitea: lleva dentro las credenciales de MinIO, y tienen que ser las de
# ESTE stack (admin/adminadmin), no las del compose de deploy/ (minioadmin). El servicio las
# usa de verdad en el perfil local, asi que un valor equivocado no es cosmetico: la subida
# falla con 503.
#
# Se genera SOLO SI FALTA. El fichero esta montado en vivo dentro del contenedor
# (services/secrets-manager-stub.yaml), asi que es el sitio donde se tocan los valores a mano;
# regenerarlo en cada ./up.sh borraria esas ediciones sin avisar. Para volver a la plantilla,
# --regen-secret. Para que el stub lea el fichero editado, ./reload-secret.sh.
if has ibm-secret-manager; then
  mkdir -p "$STUB_DIR/mappings"
  if [ -f "$STUB_SECRET" ] && [ "$REGEN_SECRET" = no ]; then
    echo "Secreto del stub conservado en infra/$STUB_SECRET (no se toca: puede tener ediciones)"
    echo "  rehacerlo desde la plantilla: ./up.sh --regen-secret ibm-secret-manager"
    echo "  aplicar cambios al stub ya levantado: ./reload-secret.sh"
  else
    [ -f "$STUB_TEMPLATE" ] || die "no encuentro $STUB_TEMPLATE"
    sed -e "s|__MINIO_USER__|$MINIO_USER|g" \
        -e "s|__MINIO_PASSWORD__|$MINIO_PASSWORD|g" \
        "$STUB_TEMPLATE" > "$STUB_SECRET"
    # Misma comprobacion que la del nginx: si la sustitucion no ocurrio, el stub serviria
    # marcadores literales y el fallo se veria mucho mas tarde, al subir un archivo.
    # En forma de `if` y no `grep ... && die`: un AND-OR list cuyo lado izquierdo falla se
    # comporta distinto segun la shell, y aqui no sabemos cual corre en DevX.
    if grep -q "__MINIO_" "$STUB_SECRET"; then
      die "quedaron marcadores sin sustituir en $STUB_SECRET"
    fi
    if [ "$REGEN_SECRET" = yes ]; then
      echo "Secreto del stub REGENERADO desde la plantilla en infra/$STUB_SECRET (MinIO: $MINIO_USER)"
    else
      echo "Secreto del stub generado en infra/$STUB_SECRET (MinIO: $MINIO_USER)"
    fi
  fi
fi

if [ "$DRY_RUN" = yes ]; then
  echo
  echo "--dry-run: no se levanta nada. Revisa infra/$COMPOSE_FILE"
  exit 0
fi

# --- arranque -------------------------------------------------------------------------
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
  RUNTIME=docker
elif command -v podman-compose >/dev/null 2>&1; then
  COMPOSE="podman-compose"
  RUNTIME=podman
else
  die "no encuentro ni 'docker compose' ni 'podman-compose'"
fi

# Las imagenes se bajan AQUI, antes del up, y con reintentos. La de Oracle son 1,3 GB de
# descarga (5,1 GB ya descomprimida), y por el proxy corporativo de DevX se corta sola a media
# capa ("unexpected EOF"). Cada reintento reaprovecha las capas ya bajadas, asi que avanza; hacerlo
# dentro del `up` no reintenta nada y ademas deja el fallo enterrado entre las barras de
# progreso de todos los servicios a la vez.
pull_image() {
  n=1
  while [ "$n" -le 3 ]; do
    if $RUNTIME pull "$1" >/dev/null; then
      echo "  $1"
      return 0
    fi
    n=$((n + 1))
    if [ "$n" -le 3 ]; then
      echo "  $1 -- fallo, reintento $n de 3 (lo ya descargado se conserva)"
    fi
  done
  return 1
}

echo
echo "Descargando imagenes..."
# Salen del compose ya generado, para no repetir aqui la lista de servicios seleccionados.
for image in $(grep -E '^    image:' "$COMPOSE_FILE" | awk '{print $2}' | sort -u); do
  pull_image "$image" || die "no se pudo descargar $image tras 3 intentos.
  Si el mensaje de arriba es un EOF o un timeout, es la descarga cortandose: vuelve a lanzar
  ./up.sh y seguira donde lo dejo. Si es un 'unsupported media type' o un 'not found', esa
  imagen no esta en el mirror corporativo: cambia su clave en infra/images.json."
done

echo
# -p infra: sin esto el proyecto tomaria el nombre del directorio (generated/) y los
# volumenes se llamarian generated_mongo-data. Con nombre fijo, down.sh encuentra siempre
# lo que up.sh creo.
if ! $COMPOSE -p infra -f "$COMPOSE_FILE" up -d; then
  die "el arranque fallo; el motivo esta justo arriba."
fi

# Y ademas se comprueba que los contenedores existen de verdad. No sobra: se ha visto un
# `up -d` terminar con codigo 0 y sin crear nada (con el pull cortado), y entonces el resumen
# de abajo estaria mintiendo, que es peor que no imprimir nada.
CREATED=$($RUNTIME ps -a --format '{{.Names}}' 2>/dev/null || true)
MISSING=""
for name in $(grep -E '^    container_name:' "$COMPOSE_FILE" | awk '{print $2}'); do
  echo "$CREATED" | grep -qx "$name" || MISSING="$MISSING $name"
done
[ -z "$MISSING" ] || die "el compose termino sin error pero faltan contenedores:$MISSING
  Revisa la salida de arriba y '$RUNTIME logs <contenedor>'."

# --- resumen --------------------------------------------------------------------------
echo
echo "============================================================================"
echo " STACK LEVANTADO   (usuario DevX: $DEVX_USER)"
echo "============================================================================"
echo
echo " UIs -- recuerda hacer \"Add Port\" en el panel PORTS de la IDE"
has redis && printf '   Redis Commander  %-56s admin / admin\n' "$(url_for 8081)"
has mongo && printf '   mongo-express    %-56s admin / admin\n' "$(url_for 8082)"
has minio && printf '   MinIO consola    %-56s %s / %s\n' "$(url_for 9001)" "$MINIO_USER" "$MINIO_PASSWORD"
has oracle && printf '   DbGate           %-56s %s / %s\n' "$(url_for 8083)" "$ORACLE_USER" "$ORACLE_PASSWORD"
echo
if has ibm-secret-manager; then
  echo " Emulacion de IBM Cloud Secrets Manager   http://localhost:8090"
  echo "   NO es una UI y NO necesita Add Port: la consume la aplicacion, no el navegador."
  echo "   El perfil 'local' ya apunta ahi por defecto, asi que no hay que exportar nada."
  echo "   Valores del secreto: infra/$STUB_SECRET"
  echo "   Ese fichero esta montado en vivo en el contenedor y SE CONSERVA entre ./up.sh:"
  echo "   editalo y lanza ./reload-secret.sh para que el stub lo relea sin recrear nada."
  echo
fi
echo " Conexiones desde OTRO CONTENEDOR de este compose (por nombre de servicio)"
has redis && echo "   Redis    redis:6379                                   sin auth"
has mongo && echo "   MongoDB  mongodb://admin:admin@mongo:27017/?authSource=admin"
has minio && echo "   MinIO    http://minio:9000   $MINIO_USER / $MINIO_PASSWORD   bucket: $BUCKET"
has oracle && echo "   Oracle   jdbc:oracle:thin:@//oracle:1521/$ORACLE_SERVICE   $ORACLE_USER / $ORACLE_PASSWORD"
echo
echo " Conexiones desde el propio workspace (puertos publicados)"
has redis && echo "   Redis    localhost:6379"
has mongo && echo "   MongoDB  mongodb://admin:admin@localhost:27017/?authSource=admin"
has minio && echo "   MinIO    http://localhost:9000"
has oracle && echo "   Oracle   jdbc:oracle:thin:@//localhost:1521/$ORACLE_SERVICE   $ORACLE_USER / $ORACLE_PASSWORD"
echo
echo " MinIO es la unica excepcion a admin/admin: rechaza contrasenas de menos de 8"
echo " caracteres, por eso es adminadmin."
has oracle && echo
has oracle && echo " El PRIMER ./up.sh oracle tarda mas: la imagen -faststart trae la base ya creada dentro,"
has oracle && echo " y el volumen oracle-data vacio la obliga a copiarla (~3 GB). La senal de que Oracle esta"
has oracle && echo " listo es \"DATABASE IS READY TO USE!\" en 'docker logs -f infra-oracle', NO que el"
has oracle && echo " contenedor aparezca Up."
has oracle && echo
has oracle && echo " El usuario $ORACLE_USER lo crea la imagen, y SOLO al inicializar el volumen vacio: igual"
has oracle && echo " que en mongo, si la UI da ORA-01017 hay que empezar limpio con ./down.sh -v"
has mongo && echo
has mongo && echo " El admin/admin de MongoDB lo crea la propia imagen oficial, y SOLO al inicializar"
has mongo && echo " el volumen mongo-data-v8 vacio. Sobre un volumen que ya tenga datos no se recrea:"
has mongo && echo " si la URI de arriba no autentica, empieza limpio con ./down.sh -v"
echo
echo " Para bajarlo:  ./down.sh        (los datos sobreviven)"
echo "                ./down.sh -v     (borra tambien los volumenes)"
echo "============================================================================"
