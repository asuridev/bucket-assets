#!/bin/sh
# Genera nginx/mongo-ui.conf desde la plantilla y levanta el stack.
#
#   ./deploy-mongo/up.sh                 # local: prefijo vacio
#   ./deploy-mongo/up.sh                 # DevX: coge el prefijo de deploy-mongo/.env.devx
#   MONGO_UI_BASE_PATH=/user/j31399/http/8082 ./deploy-mongo/up.sh    # forzandolo a mano
#
# Corre en el HOST, no dentro del contenedor: asi no depende de como el compose de turno
# expanda el "$", ni de que la imagen tenga envsubst, ni de los finales de linea del fichero.
set -e

cd "$(dirname "$0")"

ENV_FILE=.env.devx
COMPOSE_ARGS="-f docker-compose.yaml"

# El prefijo sale, por orden: de la variable de entorno, o del .env.devx si existe.
if [ -z "${MONGO_UI_BASE_PATH+x}" ] && [ -f "$ENV_FILE" ]; then
  MONGO_UI_BASE_PATH=$(grep -E '^[[:space:]]*MONGO_UI_BASE_PATH=' "$ENV_FILE" | tail -1 | cut -d= -f2-)
fi
PREFIX="${MONGO_UI_BASE_PATH:-}"
PREFIX="${PREFIX%/}"   # sin barra final: la plantilla ya la pone

# Comillas dobles con \$ escapado: el patron llega literal a sed y solo se expande $PREFIX.
# Con comillas simples no se expandiria el prefijo; con el patron sin escapar, la shell lo
# vaciaria antes de que sed lo viera. Es el error clasico al hacerlo a mano.
sed "s|\${MONGO_UI_BASE_PATH}|$PREFIX|g" nginx/default.conf.template > nginx/mongo-ui.conf

if [ -n "$PREFIX" ]; then
  COUNT=$(grep -c -- "$PREFIX" nginx/mongo-ui.conf || true)
  echo "nginx/mongo-ui.conf generado con prefijo '$PREFIX' ($COUNT lineas lo llevan)"
  if [ "$COUNT" -eq 0 ]; then
    echo "ERROR: la sustitucion no ha hecho nada. Revisa nginx/default.conf.template" >&2
    exit 1
  fi
else
  echo "nginx/mongo-ui.conf generado SIN prefijo (modo local, la UI se sirve en la raiz)"
fi

[ -f "$ENV_FILE" ] && COMPOSE_ARGS="$COMPOSE_ARGS --env-file $ENV_FILE"

# docker compose donde lo haya; si no, podman-compose (Windows).
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v podman-compose >/dev/null 2>&1; then
  COMPOSE="podman-compose"
else
  echo "ERROR: no encuentro ni 'docker compose' ni 'podman-compose'" >&2
  exit 1
fi

echo "> $COMPOSE $COMPOSE_ARGS up -d --force-recreate mongo-ui-proxy (y el resto del stack)"
# shellcheck disable=SC2086
$COMPOSE $COMPOSE_ARGS up -d
# shellcheck disable=SC2086
$COMPOSE $COMPOSE_ARGS up -d --force-recreate mongo-ui-proxy
