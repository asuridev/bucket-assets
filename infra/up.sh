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
# El segundo secreto: el service credential de Redis. Va aparte porque un
# service_credentials pertenece a UNA instancia enlazada y no puede compartir secreto con las
# credenciales del COS. Ver credenciales-ibm-cloud.md.
STUB_REDIS_TEMPLATE=$STUB_DIR/secret-redis.json.template
STUB_REDIS_SECRET=$STUB_DIR/mappings/secret-redis.json

# Certificados del Redis con TLS. Los genera este script (no se commitean) y se montan en el
# contenedor; la CA ademas viaja dentro del secreto que sirve el stub, que es como llega a la
# aplicacion en Code Engine.
REDIS_TLS_DIR=conf/redis-tls
# Usuario de ACL con el que se conecta la aplicacion, para ejercitar el
# spring.data.redis.username que exige la instancia real. Tiene que coincidir con
# services/redis.yaml y con la plantilla del secreto.
REDIS_ACL_USER=contentms
REDIS_ACL_PASSWORD=contentms-local

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
    -e "s|__REDIS_ACL_USER__|$REDIS_ACL_USER|g" \
    -e "s|__REDIS_ACL_PASSWORD__|$REDIS_ACL_PASSWORD|g" \
    "services/$1" >> "$COMPOSE_FILE"
  echo >> "$COMPOSE_FILE"
}

# --- certificados del Redis con TLS --------------------------------------------------
# La instancia real de IBM solo habla TLS, con una CA AUTOFIRMADA que la JVM no conoce y que
# viaja dentro del propio secreto. Aqui se reproduce eso: una CA propia, un certificado de
# servidor firmado por ella, y la CA metida en el secreto que sirve el stub. Sin esto, el
# ensayo local probaria el parseo del secreto pero no el truststore ni el handshake, que es
# justo la parte que no existia y mas riesgo tiene.
#
# Se generan SOLO SI FALTAN, igual que el secreto: regenerarlos en cada ./up.sh invalidaria
# la CA que ya esta dentro del secreto renderizado.
ensure_redis_tls() {
  if [ -f "$REDIS_TLS_DIR/ca.crt" ] && [ "$REGEN_SECRET" = no ]; then
    echo "Certificados de Redis conservados en infra/$REDIS_TLS_DIR"
    return 0
  fi

  command -v openssl >/dev/null 2>&1 || die "hace falta openssl para generar los certificados
  del Redis con TLS. Alternativas: instalarlo, generar a mano infra/$REDIS_TLS_DIR/{ca.crt,ca.key,redis.crt,redis.key}
  (los comandos estan en infra/README.md), o arrancar la aplicacion sin cache con CACHE_ENABLED=false"

  mkdir -p "$REDIS_TLS_DIR"

  # En Git Bash / MSYS, un argumento que empieza por "/" se convierte en ruta de Windows: el
  # -subj "/CN=infra-redis-ca" llega a openssl como "C:/Program Files/Git/CN=infra-redis-ca" y
  # falla con un mensaje sobre el formato del subject que no menciona la conversion. Estas dos
  # variables la desactivan, y en Linux no existen y no molestan.
  MSYS_NO_PATHCONV=1
  MSYS2_ARG_CONV_EXCL='*'
  export MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL

  # El SAN NO es opcional: Lettuce verifica el hostname por defecto, y la aplicacion conecta a
  # localhost:6380. Se incluye tambien `redis` (el nombre del servicio en la red del compose)
  # por si algun dia conecta desde otro contenedor.
  # El fichero de extensiones va aqui dentro y con ruta RELATIVA, no en $(mktemp): en Git Bash
  # mktemp devuelve algo como /tmp/tmp.XXXX, que es una ruta de MSYS que el openssl de Windows
  # no sabe abrir -- y con la conversion de rutas ya desactivada, nadie se la traduce.
  EXT=$REDIS_TLS_DIR/openssl.ext
  printf 'subjectAltName=DNS:localhost,DNS:redis,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' > "$EXT"

  openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
    -keyout "$REDIS_TLS_DIR/ca.key" -out "$REDIS_TLS_DIR/ca.crt" \
    -subj "/CN=infra-redis-ca" -addext "basicConstraints=critical,CA:TRUE" 2>/dev/null \
    || die "openssl no pudo crear la CA en infra/$REDIS_TLS_DIR"

  openssl req -newkey rsa:2048 -nodes \
    -keyout "$REDIS_TLS_DIR/redis.key" -out "$REDIS_TLS_DIR/redis.csr" \
    -subj "/CN=localhost" 2>/dev/null \
    || die "openssl no pudo crear la peticion de certificado del servidor"

  openssl x509 -req -in "$REDIS_TLS_DIR/redis.csr" -sha256 -days 3650 \
    -CA "$REDIS_TLS_DIR/ca.crt" -CAkey "$REDIS_TLS_DIR/ca.key" -CAcreateserial \
    -out "$REDIS_TLS_DIR/redis.crt" -extfile "$EXT" 2>/dev/null \
    || die "openssl no pudo firmar el certificado del servidor con la CA"

  rm -f "$EXT" "$REDIS_TLS_DIR/redis.csr"
  # Redis corre como usuario no root dentro del contenedor y tiene que poder leer la clave.
  chmod 644 "$REDIS_TLS_DIR/redis.key" "$REDIS_TLS_DIR/ca.key" 2>/dev/null || true

  # El secreto lleva dentro la CA, asi que una CA nueva obliga a rehacerlo: si no, el stub
  # seguiria sirviendo la vieja y el handshake fallaria en silencio (la cache degrada a miss).
  REGEN_SECRET=yes
  echo "Certificados de Redis generados en infra/$REDIS_TLS_DIR (CA autofirmada, SAN localhost)"
}

VOLUMES=""
if has redis || has ibm-secret-manager; then
  # Tambien con solo el stub: el secreto que sirve lleva la CA dentro, asi que tiene que
  # existir aunque el contenedor de Redis no se levante en esta ejecucion.
  ensure_redis_tls
fi
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
    #
    # El patron es generico (__LO_QUE_SEA__) y no "__MINIO_": hay mas de un secreto y mas de
    # un marcador, y una guarda que solo mira uno deja pasar los demas en silencio.
    if grep -qE "__[A-Z0-9_]+__" "$STUB_SECRET"; then
      die "quedaron marcadores sin sustituir en $STUB_SECRET"
    fi
    if [ "$REGEN_SECRET" = yes ]; then
      echo "Secreto del stub REGENERADO desde la plantilla en infra/$STUB_SECRET (MinIO: $MINIO_USER)"
    else
      echo "Secreto del stub generado en infra/$STUB_SECRET (MinIO: $MINIO_USER)"
    fi
  fi

  # --- segundo secreto: el service credential de Redis -------------------------------
  # Mismo criterio que el de arriba (solo si falta, --regen-secret para rehacerlo), con una
  # diferencia: este lleva dentro la CA del TLS, asi que se rehace SIEMPRE que se hayan
  # regenerado los certificados -- ensure_redis_tls() pone REGEN_SECRET=yes justo para eso.
  # Servir una CA que ya no firma nada daria un handshake fallido, y ese fallo es silencioso:
  # la cache degrada a miss y todo sigue respondiendo 200.
  if [ -f "$STUB_REDIS_SECRET" ] && [ "$REGEN_SECRET" = no ]; then
    echo "Service credential de Redis conservado en infra/$STUB_REDIS_SECRET"
  else
    [ -f "$STUB_REDIS_TEMPLATE" ] || die "no encuentro $STUB_REDIS_TEMPLATE"
    [ -f "$REDIS_TLS_DIR/ca.crt" ] || die "no encuentro infra/$REDIS_TLS_DIR/ca.crt: el
  service credential lleva la CA dentro. Levanta con 'redis' o 'ibm-secret-manager' para que
  se generen los certificados"

    # `base64 -w0` es de GNU coreutils y NO existe en BusyBox ni en macOS: el `tr` hace lo
    # mismo en todas partes. Tiene que ir en una sola linea porque acaba dentro de un JSON.
    REDIS_CA_BASE64=$(base64 < "$REDIS_TLS_DIR/ca.crt" | tr -d '\n\r')
    [ -n "$REDIS_CA_BASE64" ] || die "la codificacion en base64 de infra/$REDIS_TLS_DIR/ca.crt salio vacia"

    # El certificado va con `s|...|...|` como el resto, y por eso la CA se codifica en base64
    # ANTES: un PEM en crudo lleva saltos de linea y barras, que romperian el sed.
    sed -e "s|__REDIS_ACL_USER__|$REDIS_ACL_USER|g" \
        -e "s|__REDIS_ACL_PASSWORD__|$REDIS_ACL_PASSWORD|g" \
        -e "s|__REDIS_CA_BASE64__|$REDIS_CA_BASE64|g" \
        "$STUB_REDIS_TEMPLATE" > "$STUB_REDIS_SECRET"

    if grep -qE "__[A-Z0-9_]+__" "$STUB_REDIS_SECRET"; then
      die "quedaron marcadores sin sustituir en $STUB_REDIS_SECRET"
    fi
    echo "Service credential de Redis generado en infra/$STUB_REDIS_SECRET (usuario ACL: $REDIS_ACL_USER, CA: $REDIS_TLS_DIR/ca.crt)"
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

# Las imagenes se bajan AQUI, antes del up, y con reintentos. La de Oracle son 0,85 GB de
# descarga (1,97 GB ya descomprimida), y por el proxy corporativo de DevX se corta sola a media
# capa ("unexpected EOF"). Cada reintento reaprovecha las capas ya bajadas, asi que avanza; hacerlo
# dentro del `up` no reintenta nada y ademas deja el fallo enterrado entre las barras de
# progreso de todos los servicios a la vez.
# Se usa el `pull` del propio compose, y NO un `$RUNTIME pull` por imagen, por la salida: esta
# muestra el progreso por servicio y por capa, que es lo unico que dice si una descarga de
# varios minutos esta avanzando o colgada. Y sin redirigir nada: docker escribe el progreso por
# stdout y podman por stderr, asi que cualquier `>/dev/null` deja la pantalla muda en uno de los
# dos.
#
# Y el reintento NO se decide por el codigo de salida del pull, sino comprobando que las
# imagenes estan: `podman-compose pull` devuelve 0 aunque la descarga falle (comprobado), asi
# que fiarse de su codigo dejaria los reintentos sin disparar justo cuando hacen falta.
images_missing() {
  missing=""
  for image in $(grep -E '^    image:' "$COMPOSE_FILE" | awk '{print $2}' | sort -u); do
    $RUNTIME image inspect "$image" >/dev/null 2>&1 || missing="$missing $image"
  done
  echo "$missing"
}

ATTEMPT=1
while : ; do
  $COMPOSE -p infra -f "$COMPOSE_FILE" pull || true
  MISSING=$(images_missing)
  [ -n "$MISSING" ] || break
  ATTEMPT=$((ATTEMPT + 1))
  [ "$ATTEMPT" -le 3 ] || die "no se pudieron descargar tras 3 intentos:$MISSING
  Si el mensaje de arriba es un EOF o un timeout, es la descarga cortandose: vuelve a lanzar
  ./up.sh y seguira donde lo dejo. Si es un 'unsupported media type' o un 'not found', esa
  imagen no esta en el mirror corporativo: cambia su clave en infra/images.json."
  echo
  echo "Faltan por descargar:$MISSING"
  echo "Reintento $ATTEMPT de 3 -- las capas ya bajadas se conservan, asi que este intento"
  echo "sigue donde se quedo el anterior."
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
# Se pregunta por cada contenedor con `inspect`, y no filtrando la salida de `ps -a`: esa
# depende de una plantilla de formato y de que el `ps` haya ido bien, y una salida vacia por
# cualquier motivo se leeria como "no hay nada creado" -- un falso positivo que tapa el fallo
# de verdad. `inspect` responde por el nombre exacto y su codigo de salida no es ambiguo.
MISSING=""
for name in $(grep -E '^    container_name:' "$COMPOSE_FILE" | awk '{print $2}'); do
  $RUNTIME container inspect "$name" >/dev/null 2>&1 || MISSING="$MISSING $name"
done

# Y estar creado no basta: lo que interesa es si sigue vivo. Un contenedor que arranca y se
# muere (Oracle sin memoria suficiente, por ejemplo) deja el stack inservible aunque exista.
# minio-init es la excepcion legitima: es un trabajo de un solo uso y termina siempre.
DEAD=""
for name in $(grep -E '^    container_name:' "$COMPOSE_FILE" | awk '{print $2}'); do
  [ "$name" = infra-minio-init ] && continue
  echo " $MISSING " | grep -q " $name " && continue
  state=$($RUNTIME container inspect -f '{{.State.Status}}' "$name" 2>/dev/null || echo desconocido)
  [ "$state" = running ] || DEAD="$DEAD $name($state)"
done

if [ -n "$MISSING" ] || [ -n "$DEAD" ]; then
  echo >&2
  [ -z "$MISSING" ] || echo "No se llegaron a crear:$MISSING" >&2
  [ -z "$DEAD" ] || echo "Se crearon pero no estan corriendo:$DEAD" >&2
  echo >&2
  echo "Esto es lo que ve $RUNTIME ahora mismo:" >&2
  $RUNTIME ps -a >&2 || true
  die "el stack no quedo levantado. Mira '$RUNTIME logs <contenedor>' del que fallo."
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
has minio && printf '   MinIO consola    %-56s %s / %s\n' "$(url_for 9001)" "$MINIO_USER" "$MINIO_PASSWORD"
has oracle && printf '   DbGate           %-56s %s / %s\n' "$(url_for 8083)" "$ORACLE_USER" "$ORACLE_PASSWORD"
echo
if has ibm-secret-manager; then
  echo " Emulacion de IBM Cloud Secrets Manager   http://localhost:8090"
  echo "   NO es una UI y NO necesita Add Port: la consume la aplicacion, no el navegador."
  echo "   El perfil 'local' ya apunta ahi por defecto, asi que no hay que exportar nada."
  echo "   Valores del secreto: infra/$STUB_SECRET"
  echo "   Service credential de Redis (TLS): infra/$STUB_REDIS_SECRET"
  echo "   Esos ficheros estan montados en vivo en el contenedor y SE CONSERVAN entre ./up.sh:"
  echo "   editalos y lanza ./reload-secret.sh para que el stub los relea sin recrear nada."
  echo
fi
if has redis; then
  echo " Redis habla en DOS puertos: 6379 en claro y 6380 con TLS"
  echo "   6380 es el que usa la aplicacion, y sale del service credential del stub."
  echo "   Comprobar el TLS:  openssl s_client -connect localhost:6380 -CAfile infra/$REDIS_TLS_DIR/ca.crt"
  echo "   Ver las claves:    redis-cli --tls --cacert infra/$REDIS_TLS_DIR/ca.crt -p 6380 \\"
  echo "                        --user $REDIS_ACL_USER --pass $REDIS_ACL_PASSWORD KEYS 'contentms:*'"
  echo "   Sin cache (y sin leer el secreto de Redis): CACHE_ENABLED=false"
  echo
fi
echo " Conexiones desde OTRO CONTENEDOR de este compose (por nombre de servicio)"
has redis && echo "   Redis    redis:6379 (claro, sin auth)   redis:6380 (TLS, $REDIS_ACL_USER)"
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
has oracle && echo " El PRIMER ./up.sh oracle tarda algo mas: la imagen descomprime los datafiles en el volumen"
has oracle && echo " oracle-data (~7 s, 3,0 GB). Necesitas ~5 GB libres en el disco de Docker, no en \$HOME."
has oracle && echo " La senal de que Oracle esta listo es \"DATABASE IS READY TO USE!\" en"
has oracle && echo " 'docker logs -f infra-oracle', NO que el contenedor aparezca Up."
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
