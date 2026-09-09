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

STUB_SECRET=conf/secrets-manager-stub/mappings/secret-kv.json
ADMIN=http://localhost:8090/__admin
SECRET_PATH=/api/v2/secret_groups/default/secret_types/kv/secrets/contentms-secrets

die() { echo "ERROR: $*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "hace falta curl para hablar con el stub por HTTP"

[ -f "$STUB_SECRET" ] || die "no encuentro infra/$STUB_SECRET. Levanta el stub: ./up.sh ibm-secret-manager"

# --fail para que un 4xx/5xx sea un fallo de verdad y no un cuerpo de error impreso como si
# todo hubiera ido bien.
curl -s --fail -X POST "$ADMIN/mappings/reset" >/dev/null 2>&1 \
  || die "el stub no responde en localhost:8090. Esta levantado? docker logs infra-secrets-stub"

echo "Mappings recargados desde infra/conf/secrets-manager-stub/mappings/"
echo

# Verificacion: se imprime lo que el stub sirve AHORA, que es la unica prueba de que la
# edicion llego. Sin jq, que no se puede dar por hecho en DevX (mismo criterio que el parser
# de images.json en up.sh).
echo "Esto es lo que sirve ahora el stub:"
curl -s --fail "http://localhost:8090$SECRET_PATH" \
  || die "el reset paso, pero el stub no sirve el secreto en $SECRET_PATH. Mira el fichero: es JSON valido?"
echo
echo

echo "OJO: la aplicacion lee el secreto UNA SOLA VEZ, al arrancar (SecretsEnvironmentPostProcessor,"
echo "sin refresh). Recargar el stub no basta: reinicia tambien el servicio Spring Boot para que"
echo "los valores nuevos lleguen."
