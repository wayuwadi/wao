#!/bin/sh
set -eu

sh deploy/scripts/test-render-nginx-config.sh
sh deploy/scripts/test-set-nginx-upstream-state.sh
sh deploy/scripts/test-quiesce-wao-server.sh
sh deploy/scripts/test-windows-node-availability.sh
sh deploy/jenkins/test-ci-change-plan.sh
python3 -m unittest deploy/jumpserver/test_custom_sso.py
sh deploy/jumpserver/test-ensure-asset-key.sh
sh deploy/jumpserver/test-ensure-sso-secret.sh

for script in \
  deploy/jumpserver/deploy.sh \
  deploy/jumpserver/ensure-asset-key.sh \
  deploy/jumpserver/ensure-sso-secret.sh \
  deploy/jumpserver/install-public-key.sh \
  deploy/jumpserver/verify-assets.sh \
  deploy/scripts/ensure-jumpserver-certificate.sh \
  deploy/jenkins/ci-deploy.sh \
  deploy/jenkins/deploy-master-local.sh; do
  sh -n "$script"
done

grep -q 'server_name jms.wayuwa.cn;' deploy/nginx/jms.wayuwa.cn.conf
grep -q 'proxy_pass http://127.0.0.1:18082;' deploy/nginx/jms.wayuwa.cn.conf
grep -q 'location = /api/auth/jumpserver' deploy/nginx/wao.wayuwa.cn.conf.template
grep -q 'X-WAO-API-Route linux-jumpserver-sso' deploy/nginx/wao.wayuwa.cn.conf.template
grep -q 'location = /api/external/jumpserver/health' deploy/nginx/wao.wayuwa.cn.conf.template
grep -q 'location = /pluginWebUpdateNotice/web_version_by_plugin.json' \
  deploy/nginx/wao.wayuwa.cn.conf.template
grep -q 'location = /pluginWebUpdateNotice/web_version_by_plugin.json' \
  deploy/nginx/wao.pending.conf
grep -q 'location = /pluginWebUpdateNotice/web_version_by_plugin.json' \
  deploy/nginx/wao.bootstrap.conf
grep -Fq "printf 'DOCKER_IMAGE_MIRROR=1\\n'" deploy/jumpserver/deploy.sh
grep -Fqx 'set_config DOCKER_IMAGE_MIRROR 1' deploy/jumpserver/deploy.sh

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
