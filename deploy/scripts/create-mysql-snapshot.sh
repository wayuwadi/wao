#!/bin/sh
set -eu

output="${1:?Usage: create-mysql-snapshot.sh OUTPUT.sql.gz}"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"
env_file="${project_root}/deploy/.env"
test -f "${env_file}" || { echo "Missing ${env_file}" >&2; exit 1; }

set -a
. "${env_file}"
set +a

database="${DB_NAME:-wao}"
case "${database}" in
  *[!A-Za-z0-9_]*|'') echo 'DB_NAME must contain only letters, digits, and underscores.' >&2; exit 2 ;;
esac
dump_host="${MYSQL_DUMP_HOST:-${MASTER_BIND_IP:-127.0.0.1}}"
temporary="${output}.tmp.$$"
plain="${temporary}.sql"
trap 'rm -f "${temporary}" "${plain}"' EXIT HUP INT TERM

run_docker() {
  if docker info >/dev/null 2>&1; then
    docker "$@"
  elif sudo -n docker info >/dev/null 2>&1; then
    sudo -n docker "$@"
  else
    echo 'Docker is unavailable to the current user.' >&2
    return 1
  fi
}

run_docker run --rm --network host \
  -e MYSQL_PWD="${DB_ROOT_PASS}" \
  mysql:9.7.0 \
  mysqldump -h "${dump_host}" -uroot \
    --single-transaction --set-gtid-purged=ON --hex-blob --routines --triggers --events \
    --databases "${database}" >"${plain}"
gzip -1 <"${plain}" >"${temporary}"
rm -f "${plain}"

gzip -t "${temporary}"
mv -f "${temporary}" "${output}"
trap - EXIT HUP INT TERM
chmod 0600 "${output}"
echo "Created GTID snapshot at ${output}"
