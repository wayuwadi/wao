#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
workspace="$(mktemp -d)"
trap 'rm -rf "${workspace}"' EXIT HUP INT TERM

project="${workspace}/project"
fake_bin="${workspace}/bin"
log="${workspace}/docker.log"
mkdir -p "${project}/deploy" "${fake_bin}"
: >"${project}/deploy/.env"
: >"${project}/deploy/.env.secrets"
: >"${project}/deploy/docker-compose-master.yml"
: >"${project}/deploy/docker-compose-slave.yml"

cat >"${fake_bin}/docker" <<'SH'
#!/bin/sh
set -eu
if [ "${1:-}" = info ]; then exit 0; fi
printf '%s\n' "$*" >>"${WAO_QUIESCE_TEST_LOG}"
if printf '%s\n' "$*" | grep -q ' exec '; then
  sql="$(cat)"
  printf '%s\n' "${sql}" >>"${WAO_QUIESCE_TEST_LOG}"
  if printf '%s\n' "${sql}" | grep -q "SELECT IF(@ready, 'paused', 'required')"; then
    printf '%s\n' "${WAO_QUIESCE_PAUSE_STATUS:-paused}"
  elif printf '%s\n' "${sql}" | grep -q "SELECT IF(@ready, 'complete', 'required')"; then
    printf '%s\n' "${WAO_QUIESCE_COMPLETE_STATUS:-complete}"
  fi
fi
SH
chmod +x "${fake_bin}/docker"

WAO_QUIESCE_TEST_LOG="${log}" PATH="${fake_bin}:${PATH}" \
  sh "${script_dir}/quiesce-wao-server.sh" slave stop "${project}"
grep -Fq 'compose --env-file deploy/.env --env-file deploy/.env.secrets' "${log}"
grep -Fq 'stop --timeout 30 wao-server' "${log}"

WAO_QUIESCE_TEST_LOG="${log}" PATH="${fake_bin}:${PATH}" \
  sh "${script_dir}/quiesce-wao-server.sh" master pause-feishu-sync "${project}"
grep -Fq 'compose --profile managed-infra' "${log}"
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
grep -Fq "SELECT IF(@ready, 'complete', 'required')" "${log}"
grep -Fq 'COMMIT;' "${log}"

if WAO_QUIESCE_COMPLETE_STATUS=required WAO_QUIESCE_TEST_LOG="${log}" \
    PATH="${fake_bin}:${PATH}" \
    sh "${script_dir}/quiesce-wao-server.sh" master complete-v77-rollout "${project}"; then
  echo 'complete-v77-rollout must fail closed when database prerequisites are missing' >&2
  exit 1
fi

printf 'schema quiescing scripts: ok\n'
