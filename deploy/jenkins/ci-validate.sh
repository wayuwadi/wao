#!/bin/sh
set -eu

sh deploy/scripts/test-render-nginx-config.sh
sh deploy/scripts/test-set-nginx-upstream-state.sh
sh deploy/scripts/test-quiesce-wao-server.sh
sh deploy/jenkins/test-ci-change-plan.sh

check_change_plan_environment_contract() {
  if grep -Fq 'env[' Jenkinsfile; then
    echo 'Jenkinsfile must not use sandbox-blocked dynamic env[] assignment.' >&2
    return 1
  fi
  for key in DEPLOY_REQUIRED BACKEND_PROJECTS TEST_WAO_WEB TEST_AGENT_WEB \
    TEST_AI_RUNNER CHANGED_FILE_COUNT; do
    grep -Fq "env.WAO_${key} = pair[1]" Jenkinsfile || {
      printf 'Jenkinsfile does not explicitly assign WAO_%s.\n' "$key" >&2
      return 1
    }
  done
}

check_change_plan_environment_contract

check_migration_versions() {
  migration_dir="$1"
  label="$2"
  duplicates="$(find "$migration_dir" -maxdepth 1 -type f -name 'V*__*.sql' -printf '%f\n' \
    | sed -E 's/^V([^_]*)__.*$/\1/' | sort | uniq -d)"
  if [ -n "$duplicates" ]; then
    printf '%s Flyway migration 版本重复：%s\n' "$label" "$duplicates" >&2
    return 1
  fi
}

check_migration_versions wao-server/src/main/resources/db/migration WAO
check_migration_versions agent-server/src/main/resources/db/migration Agent

test -n "${WAO_GIT_SHA:-}"
printf '%s' "$WAO_GIT_SHA" | grep -Eq '^[0-9a-f]{40}$'
test -n "${WAO_RUN_TEMP:-}"

case "${BOOTSTRAP_WINDOWS:-false}" in
  true|false) ;;
  *) echo 'BOOTSTRAP_WINDOWS must be true or false.' >&2; exit 2 ;;
esac

case "${DEPLOY_PRODUCTION:-false}" in
  true|false) ;;
  *) echo 'DEPLOY_PRODUCTION must be true or false.' >&2; exit 2 ;;
esac

case "${ALLOW_WINDOWS_OFFLINE:-false}" in
  true|false) ;;
  *) echo 'ALLOW_WINDOWS_OFFLINE must be true or false.' >&2; exit 2 ;;
esac

if [ "${BOOTSTRAP_WINDOWS:-false}" = true ] && [ "${ALLOW_WINDOWS_OFFLINE:-false}" = true ]; then
  echo 'BOOTSTRAP_WINDOWS and ALLOW_WINDOWS_OFFLINE cannot both be true.' >&2
  exit 2
fi
