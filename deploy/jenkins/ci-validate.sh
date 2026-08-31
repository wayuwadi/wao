#!/bin/sh
set -eu

sh deploy/scripts/test-render-nginx-config.sh
sh deploy/scripts/test-set-nginx-upstream-state.sh
sh deploy/scripts/test-quiesce-wao-server.sh
sh deploy/scripts/test-linux-slave-availability.sh
sh deploy/scripts/test-install-release-linux.sh
sh deploy/jenkins/test-ci-change-plan.sh
sh deploy/jenkins/test-resolve-batch-base.sh
sh deploy/jenkins/test-notify-feishu.sh
python3 -m unittest deploy/jumpserver/test_custom_sso.py
sh deploy/jumpserver/test-ensure-asset-key.sh
sh deploy/jumpserver/test-ensure-sso-secret.sh
sh deploy/scripts/test-ensure-jumpserver-certificate.sh

for script in \
  deploy/jumpserver/deploy.sh \
  deploy/jumpserver/ensure-asset-key.sh \
  deploy/jumpserver/ensure-sso-secret.sh \
  deploy/jumpserver/install-public-key.sh \
  deploy/jumpserver/verify-assets.sh \
  deploy/scripts/test-ensure-jumpserver-certificate.sh \
  deploy/scripts/ensure-jumpserver-certificate.sh \
  deploy/scripts/install-release-linux.sh \
  deploy/jenkins/ci-deploy.sh \
  deploy/jenkins/resolve-batch-base.sh \
  deploy/jenkins/test-resolve-batch-base.sh \
  deploy/jenkins/notify-feishu.sh \
  deploy/jenkins/test-notify-feishu.sh \
  deploy/jenkins/deploy-master-local.sh; do
  sh -n "$script"
done

grep -q 'server_name jms.wayuwa.cn;' deploy/nginx/jms.wayuwa.cn.conf
grep -q 'proxy_pass http://127.0.0.1:18082;' deploy/nginx/jms.wayuwa.cn.conf
grep -Fq 'proxy_hide_header X-Frame-Options;' deploy/nginx/jms.wayuwa.cn.conf
grep -Fq "frame-ancestors 'self' https://wao.wayuwa.cn" deploy/nginx/jms.wayuwa.cn.conf
! grep -Fq 'add_header X-Frame-Options SAMEORIGIN' deploy/nginx/jms.wayuwa.cn.conf
grep -q 'location = /api/auth/jumpserver' deploy/nginx/wao.wayuwa.cn.conf.template
grep -q 'X-WAO-API-Route linux-jumpserver-sso' deploy/nginx/wao.wayuwa.cn.conf.template
grep -q 'location = /api/external/jumpserver/health' deploy/nginx/wao.wayuwa.cn.conf.template
grep -q 'location = /pluginWebUpdateNotice/web_version_by_plugin.json' \
  deploy/nginx/wao.wayuwa.cn.conf.template
grep -q 'location = /pluginWebUpdateNotice/web_version_by_plugin.json' \
  deploy/nginx/wao.pending.conf
grep -q 'location = /pluginWebUpdateNotice/web_version_by_plugin.json' \
  deploy/nginx/wao.bootstrap.conf
grep -Fq -- '-e WAO_GIT_SHA="${WAO_GIT_SHA:-}"' deploy/jenkins/ci-frontend-build.sh
grep -Fq "versionType: 'custom'" wao-web/vite.config.ts
grep -Fq 'while ! verify_frontend_version; do' deploy/jenkins/ci-production-verify.sh
grep -Fq "short_sha=\"\$(printf '%.7s' \"\$WAO_GIT_SHA\")\"" \
  deploy/jenkins/ci-production-verify.sh
grep -Fq 'Production frontend did not converge to version' \
  deploy/jenkins/ci-production-verify.sh
grep -Fq 'cleanup {' Jenkinsfile
! grep -A1 -F 'always {' Jenkinsfile | grep -Fq 'deploy/jenkins/cleanup-workspace.sh'
grep -Fq "printf 'DOCKER_IMAGE_MIRROR=1\\n'" deploy/jumpserver/deploy.sh
grep -Fqx 'set_config DOCKER_IMAGE_MIRROR 1' deploy/jumpserver/deploy.sh
grep -Fq 'python /opt/jumpserver/apps/manage.py shell' deploy/jumpserver/deploy.sh
grep -Fq 'verify_wao_sso.py' deploy/jumpserver/deploy.sh
grep -Fq "core_container_before=" deploy/jumpserver/deploy.sh
grep -Fq 'sudo -n docker restart jms_web' deploy/jumpserver/deploy.sh
grep -Fq 'sudo -n chmod 0711 "$volume_dir/core/data"' deploy/jumpserver/deploy.sh
grep -Fq 'http://127.0.0.1:18080/static/js/jquery-3.6.1.min.js' deploy/jumpserver/deploy.sh
grep -Fq 'https://jms.wayuwa.cn/static/js/jquery-3.6.1.min.js' \
  deploy/jenkins/ci-production-verify.sh
grep -Fq 'exec sudo -n sh "$project_root/deploy/jumpserver/verify-assets.sh"' \
  deploy/jenkins/deploy-master-local.sh
test "$(grep -Fc 'python /opt/jumpserver/apps/manage.py shell' \
  deploy/jumpserver/verify-assets.sh)" = 2
! grep -Fq 'python manage.py' deploy/jumpserver/verify-assets.sh
! grep -Fq 'enable-rdp.ps1' deploy/jenkins/ci-deploy.sh
python3 -m py_compile \
  deploy/jumpserver/bootstrap_wao_assets.py \
  deploy/jumpserver/verify_wao_access.py \
  deploy/jumpserver/verify_wao_sso.py
grep -Fq 'checkend 1209600' deploy/jenkins/ci-production-verify.sh
! grep -Fq '.clear()' deploy/jumpserver/bootstrap_wao_assets.py

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

case "${BOOTSTRAP_SLAVE:-false}" in
  true|false) ;;
  *) echo 'BOOTSTRAP_SLAVE must be true or false.' >&2; exit 2 ;;
esac

case "${DEPLOY_PRODUCTION:-false}" in
  true|false) ;;
  *) echo 'DEPLOY_PRODUCTION must be true or false.' >&2; exit 2 ;;
esac

case "${ALLOW_SLAVE_OFFLINE:-false}" in
  true|false) ;;
  *) echo 'ALLOW_SLAVE_OFFLINE must be true or false.' >&2; exit 2 ;;
esac

if [ "${BOOTSTRAP_SLAVE:-false}" = true ] && [ "${ALLOW_SLAVE_OFFLINE:-false}" = true ]; then
  echo 'BOOTSTRAP_SLAVE and ALLOW_SLAVE_OFFLINE cannot both be true.' >&2
  exit 2
fi
