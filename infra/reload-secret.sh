#!/bin/sh
# Hace que el stub de Secrets Manager relea desde disco el secreto que sirve, SIN recrear ni
# reiniciar el contenedor.
#
#   ./reload-secret.sh
#
# Por que hace falta: conf/secrets-manager-stub/ esta montado en vivo dentro del contenedor
# (services/secrets-manager-stub.yaml), asi que editar mappings/secret-kv.json ya cambia el
# fichero que el contenedor ve. Pero WireMock carga los mappings EN MEMORIA al arrancar y no
# vigila el disco, asi que hasta que no se le pide releer sigue sirviendo los valores viejos.
# POST /__admin/mappings/reset es exactamente eso: vuelve a cargar los mappings del file source.
#
# Por HTTP y no por `exec`: en DevX no hay exec en los contenedores, solo los puertos publicados.
set -e

cd "$(dirname "$0")"

STUB_DIR=conf/secrets-manager-stub
STUB_SECRET=$STUB_DIR/mappings/secret-kv.json
STUB_REDIS_SECRET=$STUB_DIR/mappings/secret-redis.json
BASE=http://localhost:8090
SECRET_PATH=/api/v2/secret_groups/default/secret_types/kv/secrets/contentms-secrets
# El service credential de Redis es un secreto DISTINTO, con su propio tipo en la ruta. El
# reset de mappings recarga los dos de una vez, pero la verificacion tiene que mirar los dos:
# si solo comprobara el kv, un secret-redis.json roto pasaria desapercibido aqui y el fallo
# aparecerian mucho mas tarde, como una cache que no cachea nada.
REDIS_SECRET_PATH=/api/v2/secret_groups/default/secret_types/service_credentials/secrets/contentms-redis-credentials

die() { echo "ERROR: $*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "hace falta curl para hablar con el stub por HTTP"

[ -f "$STUB_SECRET" ] || die "no encuentro infra/$STUB_SECRET. Levanta el stub: ./up.sh ibm-secret-manager"

BODY=$(mktemp)
trap 'rm -f "$BODY"' EXIT

# Sin --fail, y mirando el codigo a mano: los dos fallos posibles piden mensajes distintos y
# --fail los aplana en el mismo "exit 22". Un fallo de conexion es que el stub no esta; un 500
# es que el stub esta y ha rechazado lo que hay en mappings/ (tipicamente, un JSON invalido).
HTTP=$(curl -s -o "$BODY" -w '%{http_code}' -X POST "$BASE/__admin/mappings/reset") \
  || die "el stub no responde en $BASE. Esta levantado? docker logs infra-secrets-stub"

if [ "$HTTP" != 200 ]; then
  # El 500 trae una pagina HTML con el stack de Jetty entero, pero dentro esta la unica
  # linea que importa: que fichero de mappings/ no ha podido parsear. Se extrae esa.
  DETALLE=$(grep -m1 -o "Error loading file [^<]*" "$BODY" || true)
  if [ -n "$DETALLE" ]; then echo "  $DETALLE" >&2; fi
  die "el stub rechazo la recarga (HTTP $HTTP): hay un mapping invalido en infra/$STUB_DIR/mappings/. Mas detalle: docker logs infra-secrets-stub"
fi

echo "Mappings recargados desde infra/$STUB_DIR/mappings/"
echo

# Verificacion: se imprime lo que el stub sirve AHORA, que es la unica prueba de que la
# edicion llego. Sin jq, que no se puede dar por hecho en DevX (mismo criterio que el parser
# de images.json en up.sh).
HTTP=$(curl -s -o "$BODY" -w '%{http_code}' "$BASE$SECRET_PATH") \
  || die "el stub dejo de responder en $BASE"

[ "$HTTP" = 200 ] \
  || die "la recarga paso, pero el stub ya no sirve el secreto en $SECRET_PATH (HTTP $HTTP). Cambio el urlPath del mapping?"

echo "Esto es lo que sirve ahora el stub:"
cat "$BODY"
echo
echo

# El segundo secreto solo se comprueba si existe: ./up.sh ibm-secret-manager lo genera, pero
# un stack antiguo puede no tenerlo todavia, y eso no es un error de este script.
if [ -f "$STUB_REDIS_SECRET" ]; then
  HTTP=$(curl -s -o "$BODY" -w '%{http_code}' "$BASE$REDIS_SECRET_PATH") \
    || die "el stub dejo de responder en $BASE"

  [ "$HTTP" = 200 ] \
    || die "la recarga paso, pero el stub no sirve el service credential de Redis en $REDIS_SECRET_PATH (HTTP $HTTP). Cambio el urlPath del mapping, o secrets.redis.name?"

  echo "Y este es el service credential de Redis:"
  # El certificado en base64 ocupa varias pantallas y no aporta nada leerlo entero: se
  # recorta. Si hace falta verlo, esta en el fichero.
  cut -c1-200 "$BODY"
  echo
  echo "  (recortado a 200 caracteres: el certificate_base64 es largo)"
  echo
fi

echo "OJO: la aplicacion lee el secreto UNA SOLA VEZ, al arrancar (SecretsEnvironmentPostProcessor,"
echo "sin refresh). Recargar el stub no basta: reinicia tambien el servicio Spring Boot para que"
echo "los valores nuevos lleguen."
