#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
workspace="$(mktemp -d)"
trap 'rm -rf "${workspace}"' EXIT HUP INT TERM

project="${workspace}/project"
fake_bin="${workspace}/bin"
log="${workspace}/docker.log"
tmp_dir="${workspace}/tmp"
mkdir -p "${project}/deploy" "${fake_bin}" "${tmp_dir}"
: >"${project}/deploy/.env"
: >"${project}/deploy/.env.secrets"
: >"${project}/deploy/docker-compose-master.yml"
: >"${project}/deploy/docker-compose-slave.yml"

cat >"${fake_bin}/docker" <<'SH'
#!/bin/sh
set -eu
if [ "${1:-}" = info ]; then exit 0; fi
printf '%s\n' "$*" >>"${WAO_QUIESCE_TEST_LOG}"
if [ "${1:-}" = run ] && printf '%s\n' "$*" | grep -q -- '--entrypoint mysql mysql:9.7.0'; then
  previous=""
  for argument in "$@"; do
    if [ "${previous}" = --env-file ]; then
      test -f "${argument}"
      test "$(stat -c '%a' "${argument}")" = 600
    fi
    previous="${argument}"
  done
  if [ "${WAO_QUIESCE_DOCKER_RUN_FAIL:-false}" = true ]; then
    exit 42
  fi
fi
if printf '%s\n' "$*" | grep -q ' exec ' || \
    printf '%s\n' "$*" | grep -q -- '--entrypoint mysql mysql:9.7.0'; then
  sql="$(cat)"
  printf '%s\n' "${sql}" >>"${WAO_QUIESCE_TEST_LOG}"
  if printf '%s\n' "${sql}" | grep -q "SELECT IF(@ready, 'paused', 'required')"; then
    printf '%s\n' "${WAO_QUIESCE_PAUSE_STATUS:-paused}"
  elif printf '%s\n' "${sql}" | grep -q "SELECT IF(@ready OR @already_complete, 'complete', 'required')"; then
    printf '%s\n' "${WAO_QUIESCE_COMPLETE_STATUS:-complete}"
  elif printf '%s\n' "${sql}" | grep -q "WHERE version = '77' AND success = 1"; then
    printf '%s\n' "${WAO_QUIESCE_ROLLOUT_STATUS:-required}"
  fi
fi
SH
chmod +x "${fake_bin}/docker"

cat >"${project}/deploy/.env" <<'ENV'
MANAGE_MASTER_INFRA=true
ENV

WAO_QUIESCE_TEST_LOG="${log}" PATH="${fake_bin}:${PATH}" \
  sh "${script_dir}/quiesce-wao-server.sh" slave stop "${project}"
grep -Fq 'compose --env-file deploy/.env --env-file deploy/.env.secrets' "${log}"
grep -Fq 'stop --timeout 30 wao-server' "${log}"

WAO_QUIESCE_TEST_LOG="${log}" PATH="${fake_bin}:${PATH}" \
  sh "${script_dir}/quiesce-wao-server.sh" master pause-feishu-sync "${project}"
grep -Fq 'compose --profile managed-infra' "${log}"
grep -Fq 'exec -T mysql-master' "${log}"
grep -Fq "deployment.v77.feishu-sync-status" "${log}"
grep -Fq "INSERT IGNORE INTO system_setting" "${log}"
grep -Fq "deployment.v77.rollout-state" "${log}"
grep -Fq "UPDATE automation_job SET status = '已暂停'" "${log}"
grep -Fq "SELECT IF(@ready, 'paused', 'required')" "${log}"

if WAO_QUIESCE_PAUSE_STATUS=required WAO_QUIESCE_TEST_LOG="${log}" \
    PATH="${fake_bin}:${PATH}" \
    sh "${script_dir}/quiesce-wao-server.sh" master pause-feishu-sync "${project}"; then
  echo 'pause-feishu-sync must fail closed when the shared job cannot be saved' >&2
  exit 1
fi

WAO_QUIESCE_TEST_LOG="${log}" PATH="${fake_bin}:${PATH}" \
  sh "${script_dir}/quiesce-wao-server.sh" master restore-feishu-sync "${project}"
grep -Fq "DELETE FROM system_setting" "${log}"

WAO_QUIESCE_TEST_LOG="${log}" PATH="${fake_bin}:${PATH}" \
  sh "${script_dir}/quiesce-wao-server.sh" master complete-v77-rollout "${project}"
grep -Fq "cfg_value = 'complete'" "${log}"
grep -Fq 'SET @migration_count' "${log}"
grep -Fq 'SET @saved_count' "${log}"
grep -Fq 'SET @job_count' "${log}"
grep -Fq 'SET @already_complete' "${log}"
grep -Fq '@migration_count = 1 AND @saved_count = 0 AND @job_count = 1' "${log}"
grep -Fq "SELECT IF(@ready OR @already_complete, 'complete', 'required')" "${log}"
grep -Fq 'COMMIT;' "${log}"

if WAO_QUIESCE_COMPLETE_STATUS=required WAO_QUIESCE_TEST_LOG="${log}" \
    PATH="${fake_bin}:${PATH}" \
    sh "${script_dir}/quiesce-wao-server.sh" master complete-v77-rollout "${project}"; then
  echo 'complete-v77-rollout must fail closed when database prerequisites are missing' >&2
  exit 1
fi

: >"${log}"
cat >"${project}/deploy/.env" <<'ENV'
MANAGE_MASTER_INFRA=false
MYSQL_HOST=host.docker.internal
MYSQL_PORT=4407
DB_PASS=external-db-secret-must-not-leak
ENV

WAO_QUIESCE_TEST_LOG="${log}" PATH="${fake_bin}:${PATH}" \
  TMPDIR="${tmp_dir}" sh "${script_dir}/quiesce-wao-server.sh" master pause-feishu-sync "${project}"
WAO_QUIESCE_TEST_LOG="${log}" PATH="${fake_bin}:${PATH}" \
  TMPDIR="${tmp_dir}" sh "${script_dir}/quiesce-wao-server.sh" master rollout-status "${project}"
WAO_QUIESCE_TEST_LOG="${log}" PATH="${fake_bin}:${PATH}" \
  TMPDIR="${tmp_dir}" sh "${script_dir}/quiesce-wao-server.sh" master restore-feishu-sync "${project}"
WAO_QUIESCE_TEST_LOG="${log}" PATH="${fake_bin}:${PATH}" \
  TMPDIR="${tmp_dir}" sh "${script_dir}/quiesce-wao-server.sh" master complete-v77-rollout "${project}"

grep -Fq 'run --rm -i --network wao-master --add-host host.docker.internal:host-gateway' "${log}"
grep -Fq -- '--entrypoint mysql mysql:9.7.0' "${log}"
grep -Fq -- '--protocol=TCP --connect-timeout=10' "${log}"
grep -Fq -- '--host=host.docker.internal --port=4407 --user=wao_app wao' "${log}"
if grep -Fq -- '--profile managed-infra' "${log}"; then
  echo 'external MySQL mode must not enable the managed-infra profile' >&2
  exit 1
fi
if grep -Eq 'compose .* (run|exec).*mysql-master' "${log}"; then
  echo 'external MySQL mode must not run or exec the disabled mysql-master service' >&2
  exit 1
fi
if grep -Fq 'external-db-secret-must-not-leak' "${log}"; then
  echo 'external MySQL credentials must not be written to command logs' >&2
  exit 1
fi
if find "${tmp_dir}" -mindepth 1 -print -quit | grep -q .; then
  echo 'external MySQL password files must be removed after successful commands' >&2
  exit 1
fi

if WAO_QUIESCE_DOCKER_RUN_FAIL=true WAO_QUIESCE_TEST_LOG="${log}" \
    PATH="${fake_bin}:${PATH}" TMPDIR="${tmp_dir}" \
    sh "${script_dir}/quiesce-wao-server.sh" master rollout-status "${project}"; then
  echo 'external MySQL client failures must propagate to the caller' >&2
  exit 1
fi
if find "${tmp_dir}" -mindepth 1 -print -quit | grep -q .; then
  echo 'external MySQL password files must be removed after failed commands' >&2
  exit 1
fi

printf 'schema quiescing scripts: ok\n'
