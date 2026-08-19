#!/bin/sh
set -eu

role="${1:?Usage: quiesce-wao-server.sh ROLE ACTION [PROJECT_ROOT]}"
action="${2:?Usage: quiesce-wao-server.sh ROLE ACTION [PROJECT_ROOT]}"
project_root="${3:-}"

case "${role}" in
  master)
    compose_file="deploy/docker-compose-master.yml"
    compose_profile="managed-infra"
    if [ -z "${project_root}" ]; then
      desktop_dir="$(xdg-user-dir DESKTOP 2>/dev/null || printf '%s' "${HOME}/Desktop")"
      project_root="${desktop_dir}/deploy/wao"
    fi
    ;;
  slave)
    compose_file="deploy/docker-compose-slave.yml"
    compose_profile=""
    : "${project_root:=/mnt/d/deploy/wao}"
    ;;
  *) printf 'Unknown role: %s\n' "${role}" >&2; exit 2 ;;
esac

case "${project_root}" in
  ''|/) printf 'Unsafe project root: %s\n' "${project_root}" >&2; exit 2 ;;
esac
test -f "${project_root}/${compose_file}" || {
  printf 'Compose file not found below %s\n' "${project_root}" >&2
  exit 1
}
test -f "${project_root}/deploy/.env" || {
  printf 'Deployment environment not found below %s\n' "${project_root}" >&2
  exit 1
}
cd "${project_root}"

run_docker() {
  if docker info >/dev/null 2>&1; then
    docker "$@"
  elif sudo -n docker info >/dev/null 2>&1; then
    sudo -n docker "$@"
  else
    echo 'Docker is unavailable for schema quiescing.' >&2
    return 1
  fi
}

compose() {
  legacy_env="deploy/.env.feishu"
  secrets_env="deploy/.env.secrets"
  if [ -n "${compose_profile}" ] && [ -f "${legacy_env}" ] && [ -f "${secrets_env}" ]; then
    run_docker compose --profile "${compose_profile}" --env-file deploy/.env \
      --env-file "${legacy_env}" --env-file "${secrets_env}" -f "${compose_file}" "$@"
  elif [ -n "${compose_profile}" ] && [ -f "${secrets_env}" ]; then
    run_docker compose --profile "${compose_profile}" --env-file deploy/.env \
      --env-file "${secrets_env}" -f "${compose_file}" "$@"
  elif [ -n "${compose_profile}" ] && [ -f "${legacy_env}" ]; then
    run_docker compose --profile "${compose_profile}" --env-file deploy/.env \
      --env-file "${legacy_env}" -f "${compose_file}" "$@"
  elif [ -n "${compose_profile}" ]; then
    run_docker compose --profile "${compose_profile}" --env-file deploy/.env \
      -f "${compose_file}" "$@"
  elif [ -f "${legacy_env}" ] && [ -f "${secrets_env}" ]; then
    run_docker compose --env-file deploy/.env --env-file "${legacy_env}" \
      --env-file "${secrets_env}" -f "${compose_file}" "$@"
  elif [ -f "${secrets_env}" ]; then
    run_docker compose --env-file deploy/.env --env-file "${secrets_env}" \
      -f "${compose_file}" "$@"
  elif [ -f "${legacy_env}" ]; then
    run_docker compose --env-file deploy/.env --env-file "${legacy_env}" \
      -f "${compose_file}" "$@"
  else
    run_docker compose --env-file deploy/.env -f "${compose_file}" "$@"
  fi
}

case "${action}" in
  stop)
    compose stop --timeout 30 wao-server
    ;;
  start)
    compose up -d --no-build --wait wao-server
    ;;
  pause-feishu-sync)
    test "${role}" = master || { echo 'Only the master can pause the shared job.' >&2; exit 2; }
    pause_output="$(compose exec -T mysql-master sh -ec \
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" --batch --skip-column-names' <<'SQL'
START TRANSACTION;
INSERT IGNORE INTO system_setting (cfg_key, cfg_value, updated_at)
SELECT 'deployment.v77.feishu-sync-status', status, CURRENT_TIMESTAMP(6)
  FROM automation_job WHERE id = 'job-feishu-chat-sync';
SET @saved_count = (
  SELECT COUNT(*) FROM system_setting WHERE cfg_key = 'deployment.v77.feishu-sync-status'
);
SET @job_count = (
  SELECT COUNT(*) FROM automation_job WHERE id = 'job-feishu-chat-sync'
);
SET @ready = (@saved_count = 1 AND @job_count = 1);
INSERT INTO system_setting (cfg_key, cfg_value, updated_at)
SELECT 'deployment.v77.rollout-state', 'in-progress', CURRENT_TIMESTAMP(6)
 WHERE @ready
ON DUPLICATE KEY UPDATE cfg_value = 'in-progress', updated_at = CURRENT_TIMESTAMP(6);
UPDATE automation_job SET status = '已暂停'
 WHERE id = 'job-feishu-chat-sync' AND @ready;
COMMIT;
SELECT IF(@ready, 'paused', 'required');
SQL
    )" || exit 1
    pause_status="$(printf '%s\n' "${pause_output}" | tr -d '\r' | tail -n 1)"
    test "${pause_status}" = paused || {
      printf 'Feishu sync job state could not be saved; V77 rollout has not started.\n' >&2
      exit 1
    }
    ;;
  rollout-status)
    test "${role}" = master || { echo 'Only the master can inspect the shared rollout.' >&2; exit 2; }
    compose exec -T mysql-master sh -ec \
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" --batch --skip-column-names' <<'SQL'
SELECT CASE
         WHEN EXISTS (
           SELECT 1 FROM flyway_schema_history
            WHERE version = '77' AND success = 1
         ) AND EXISTS (
           SELECT 1 FROM system_setting
            WHERE cfg_key = 'deployment.v77.rollout-state' AND cfg_value = 'complete'
         ) THEN 'complete'
         ELSE 'required'
       END;
SQL
    ;;
  restore-feishu-sync)
    test "${role}" = master || { echo 'Only the master can restore the shared job.' >&2; exit 2; }
    compose exec -T mysql-master sh -ec \
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE"' <<'SQL'
UPDATE automation_job AS job
JOIN system_setting AS saved
  ON saved.cfg_key = 'deployment.v77.feishu-sync-status'
   SET job.status = saved.cfg_value
 WHERE job.id = 'job-feishu-chat-sync';
DELETE FROM system_setting WHERE cfg_key = 'deployment.v77.feishu-sync-status';
SQL
    ;;
  complete-v77-rollout)
    test "${role}" = master || { echo 'Only the master can complete the shared rollout.' >&2; exit 2; }
    completion_output="$(compose exec -T mysql-master sh -ec \
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" --batch --skip-column-names' <<'SQL'
START TRANSACTION;
SET @migration_count = (
  SELECT COUNT(*) FROM flyway_schema_history WHERE version = '77' AND success = 1
);
SET @saved_count = (
  SELECT COUNT(*) FROM system_setting WHERE cfg_key = 'deployment.v77.feishu-sync-status'
);
SET @job_count = (
  SELECT COUNT(*) FROM automation_job WHERE id = 'job-feishu-chat-sync'
);
SET @ready = (@migration_count = 1 AND @saved_count = 1 AND @job_count = 1);
UPDATE automation_job AS job
JOIN system_setting AS saved
  ON saved.cfg_key = 'deployment.v77.feishu-sync-status'
   SET job.status = saved.cfg_value
 WHERE job.id = 'job-feishu-chat-sync' AND @ready;
INSERT INTO system_setting (cfg_key, cfg_value, updated_at)
SELECT 'deployment.v77.rollout-state', 'complete', CURRENT_TIMESTAMP(6)
 WHERE @ready
ON DUPLICATE KEY UPDATE cfg_value = 'complete', updated_at = CURRENT_TIMESTAMP(6);
DELETE FROM system_setting
 WHERE cfg_key = 'deployment.v77.feishu-sync-status' AND @ready;
COMMIT;
SELECT IF(@ready, 'complete', 'required');
SQL
    )" || exit 1
    completion_status="$(printf '%s\n' "${completion_output}" | tr -d '\r' | tail -n 1)"
    test "${completion_status}" = complete || {
      printf 'V77 rollout prerequisites are incomplete; Feishu sync remains paused.\n' >&2
      exit 1
    }
    ;;
  *) printf 'Unknown action: %s\n' "${action}" >&2; exit 2 ;;
esac
