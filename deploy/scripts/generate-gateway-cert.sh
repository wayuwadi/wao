#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
deploy_dir="$(CDPATH= cd -- "${script_dir}/.." && pwd)"
env_file="${deploy_dir}/.env"
cert_dir="${deploy_dir}/certs"

test -f "${env_file}" || { echo "Missing ${env_file}" >&2; exit 1; }
mkdir -p "${cert_dir}"

docker run --rm \
  --env-file "${env_file}" \
  -v "${cert_dir}:/certs" \
  eclipse-temurin:17-jdk-alpine \
  /bin/sh -ec 'keytool -genkeypair -noprompt -alias wao-gateway -keyalg RSA -keysize 2048 -validity 825 -storetype PKCS12 -keystore /certs/gateway.p12 -storepass "$GATEWAY_SSL_PASS" -keypass "$GATEWAY_SSL_PASS" -dname "CN=wao-gateway, OU=WAO, O=Wayuwa, C=CN"'

# The gateway runs as a non-root container user. The PKCS12 content remains
# encrypted by GATEWAY_SSL_PASS, while the deployment directory stays private.
chmod 644 "${cert_dir}/gateway.p12"
echo "Generated ${cert_dir}/gateway.p12"
