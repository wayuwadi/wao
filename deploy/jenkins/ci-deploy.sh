#!/usr/bin/env bash
set -u

# Shared with clean Nacos backups: do not restart applications during its stop window.
if [[ -f /run/lock/wao-infra-maintenance.lock ]]; then
  exec 7>/run/lock/wao-infra-maintenance.lock || exit 1
  flock -s -w 600 7 || { echo '基础设施维护未结束，拒绝重启应用。'; exit 1; }
fi

: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"
: "${WAO_RUN_TEMP:?WAO_RUN_TEMP is required}"
: "${WAO_MASTER_PROJECT_ROOT:?WAO_MASTER_PROJECT_ROOT is required}"
: "${CLOUD_SSH_KEY:?CLOUD_SSH_KEY is required}"
: "${CLOUD_KNOWN_HOSTS:?CLOUD_KNOWN_HOSTS is required}"
: "${CLOUD_USER:?CLOUD_USER is required}"
: "${CLOUD_HOST:?CLOUD_HOST is required}"

WAO_BUILD_ATTEMPT="${WAO_BUILD_ATTEMPT:-1}"
umask 077
install -d "$WAO_RUN_TEMP"
test -f "$WAO_RUN_TEMP/wao-release.tgz"
test -f "$WAO_MASTER_PROJECT_ROOT/deploy/.env"
test -f "$WAO_MASTER_PROJECT_ROOT/deploy/.env.secrets"
test -f "$WAO_MASTER_PROJECT_ROOT/deploy/certs/gateway.p12"

ln -sf "$WAO_RUN_TEMP/wao-release.tgz" wao-release.tgz
install -m 0640 "$WAO_MASTER_PROJECT_ROOT/deploy/.env.secrets" wao-secrets.env
install -m 0640 "$WAO_MASTER_PROJECT_ROOT/deploy/.env" wao-shared.env
install -m 0640 "$WAO_MASTER_PROJECT_ROOT/deploy/certs/gateway.p12" wao-gateway.p12

WAO_SSH_CONFIG="$WAO_RUN_TEMP/ssh_config"
cat >"$WAO_SSH_CONFIG" <<EOF
Host wao-cloud
  HostName $CLOUD_HOST
  User $CLOUD_USER
  IdentityFile $CLOUD_SSH_KEY
  IdentitiesOnly yes
  UserKnownHostsFile $CLOUD_KNOWN_HOSTS
  StrictHostKeyChecking yes
  ControlMaster auto
  ControlPath /tmp/wao-ci-%C
  ControlPersist 120
EOF
chmod 0600 "$WAO_SSH_CONFIG"

ssh() { command ssh -F "$WAO_SSH_CONFIG" "$@"; }
scp() { command scp -F "$WAO_SSH_CONFIG" "$@"; }

cleanup_deploy_inputs() {
  ssh -O exit wao-cloud >/dev/null 2>&1 || true
  rm -f -- wao-release.tgz wao-secrets.env wao-shared.env wao-gateway.p12
}
trap cleanup_deploy_inputs EXIT HUP INT TERM

status_file="$WAO_RUN_TEMP/wao-deploy-status.env"
printf '%s\n' \
  'linux2_status=not_started' 'linux2_attempts=0' 'linux2_detail=not_started' \
  'cloud_status=not_started' 'cloud_detail=not_started' \
  'verification_status=not_started' 'verification_detail=not_started' >"$status_file"

set_status() {
  local key="$1" value
  value="$(printf '%s' "$2" | tr '\r\n=' '   ' | cut -c1-500)"
  sed -i "/^${key}=/d" "$status_file"
  printf '%s=%s\n' "$key" "$value" >>"$status_file"
}

retry_remote() {
  local target_label="$1" attempt=1
  shift
  while ! "$@"; do
    if [ "$attempt" -ge 3 ]; then
      printf '%s SSH/SCP 连续 %s 次失败，停止重试\n' "$target_label" "$attempt"
      return 1
    fi
    printf '%s SSH/SCP 第 %s 次失败，10 秒后重试\n' "$target_label" "$attempt"
    attempt=$((attempt + 1))
    sleep 10
  done
}

batch_changed() {
  local changed
  changed="$(git diff --name-only "${WAO_CHANGE_BASE:-}" "$WAO_GIT_SHA" 2>/dev/null)" || return 0
  printf '%s\n' "$changed" | grep -Eq "$1"
}

install_linux2_deploy_helper() {
  if cmp -s deploy/jenkins/deploy-master-local.sh /usr/local/lib/wao-jenkins/deploy-master-local.sh; then
    return 0
  fi
  local workspace
  workspace="$(pwd -P)"
  docker run --rm --privileged --user 0 \
    -v /:/host -v "$workspace:/workspace:ro" \
    --entrypoint /bin/sh "wao-server:$WAO_GIT_SHA" -ceu '
      cp /workspace/deploy/jenkins/deploy-master-local.sh \
        /host/usr/local/lib/wao-jenkins/deploy-master-local.sh
      chown 0:0 /host/usr/local/lib/wao-jenkins/deploy-master-local.sh
      chmod 0755 /host/usr/local/lib/wao-jenkins/deploy-master-local.sh
    '
}

provision_jumpserver_asset_access() {
  local public_key="$WAO_MASTER_PROJECT_ROOT/deploy/.jumpserver-asset-key.pub"
  test -s "$public_key"

  retry_remote '云入口' scp -o ConnectTimeout=15 \
    "$public_key" deploy/jumpserver/install-public-key.sh wao-cloud:/tmp/ || return
  retry_remote '云入口' ssh -o ConnectTimeout=15 wao-cloud \
    'sh /tmp/install-public-key.sh /tmp/.jumpserver-asset-key.pub'
}

verify_jumpserver_assets() {
  sudo -n -u wadi /usr/local/lib/wao-jenkins/deploy-master-local.sh \
    --verify-jumpserver-assets "$WAO_MASTER_PROJECT_ROOT"
}

deploy_linux2() {
  sudo -n -u wadi /usr/local/lib/wao-jenkins/deploy-master-local.sh \
    "$WAO_MASTER_PROJECT_ROOT" wao-release.tgz wao-secrets.env "$WAO_GIT_SHA" \
    "$CLOUD_USER" "$CLOUD_HOST" "$sync_jumpserver" "$sync_infrastructure"
}

retire_cloud_sentinel() {
  retry_remote '云端 Redis Sentinel 退役' ssh -o ConnectTimeout=15 wao-cloud '
    if systemctl cat wao-cloud-sentinel.service >/dev/null 2>&1; then
      systemctl disable --now wao-cloud-sentinel.service
      echo "Disabled retired cloud Redis Sentinel; persistent data was left untouched."
    else
      echo "No cloud Redis Sentinel service is installed."
    fi
  '
}

retry_linux2_deploy() {
  local attempt=1 log_file="$1"
  : >"$log_file"
  while [ "$attempt" -le 3 ]; do
    printf '===== Linux2 deployment attempt %s/3 =====\n' "$attempt" | tee -a "$log_file"
    if (set -o pipefail; deploy_linux2 2>&1 | tee -a "$log_file"); then
      set_status linux2_attempts "$attempt"
      return 0
    fi
    if [ "$attempt" -lt 3 ]; then
      printf 'Linux2 deployment failed; retrying in %s seconds.\n' "$((attempt * 15))" | tee -a "$log_file"
      sleep "$((attempt * 15))"
    fi
    attempt=$((attempt + 1))
  done
  set_status linux2_attempts 3
  return 1
}

prepare_cloud_routing() {
  retry_remote '云端路由配置' scp -o ConnectTimeout=15 \
    deploy/nginx/wao.wayuwa.cn.conf.template deploy/nginx/jms.wayuwa.cn.conf \
    deploy/scripts/activate-nginx-config.sh deploy/scripts/render-nginx-config.sh \
    deploy/scripts/resolve-gateway-ipv4.sh deploy/scripts/ensure-jumpserver-certificate.sh \
    deploy/infrastructure/health-watch.py wao-cloud:/tmp/ || return
  retry_remote '云端路由配置' ssh -o ConnectTimeout=15 wao-cloud '
    set -eu
    install -d -m 0755 /opt/wao/deploy/nginx /opt/wao/deploy/scripts /opt/wao-health-watch
    install -m 0644 /tmp/wao.wayuwa.cn.conf.template /opt/wao/deploy/nginx/wao.wayuwa.cn.conf.template
    install -m 0644 /tmp/jms.wayuwa.cn.conf /opt/wao/deploy/nginx/jms.wayuwa.cn.conf
    install -m 0755 /tmp/activate-nginx-config.sh /opt/wao/deploy/scripts/activate-nginx-config.sh
    install -m 0755 /tmp/render-nginx-config.sh /opt/wao/deploy/scripts/render-nginx-config.sh
    install -m 0755 /tmp/resolve-gateway-ipv4.sh /opt/wao/deploy/scripts/resolve-gateway-ipv4.sh
    install -m 0755 /tmp/ensure-jumpserver-certificate.sh /opt/wao/deploy/scripts/ensure-jumpserver-certificate.sh
    install -m 0644 /tmp/health-watch.py /opt/wao-health-watch/health-watch.py
    rm -f /tmp/wao.wayuwa.cn.conf.template /tmp/jms.wayuwa.cn.conf \
      /tmp/activate-nginx-config.sh /tmp/render-nginx-config.sh \
      /tmp/resolve-gateway-ipv4.sh /tmp/ensure-jumpserver-certificate.sh /tmp/health-watch.py
    install -d -m 0755 /etc/nginx/wao-external-apps.d
    rm -f /etc/nginx/wao-external-apps.d/wao-game.conf \
      /etc/nginx/wao-external-apps.d/wao-insight.conf
    install -d -m 0755 /run/wao-nginx-upstreams
    touch /run/wao-nginx-upstreams/deploying
  '
}

stage_cloud_static() {
  retry_remote '云端静态制品' scp -o ConnectTimeout=15 \
    "$WAO_RUN_TEMP/wao-static.tgz" deploy/scripts/static-release.py \
    deploy/scripts/publish-static-routing.py wao-cloud:/tmp/ || return
  retry_remote '云端静态制品校验' ssh -o ConnectTimeout=15 wao-cloud \
    "python3 /tmp/static-release.py install /tmp/wao-static.tgz /var/www/wao/releases '$WAO_GIT_SHA'" || return
  retry_remote '云端 Nginx 配置' scp -o ConnectTimeout=15 \
    deploy/nginx/wao.wayuwa.cn.conf.template deploy/nginx/jms.wayuwa.cn.conf \
    deploy/scripts/activate-nginx-config.sh deploy/scripts/render-nginx-config.sh \
    deploy/scripts/resolve-gateway-ipv4.sh deploy/scripts/ensure-jumpserver-certificate.sh \
    wao-cloud:/tmp/
}

stage_cloud() {
  python3 deploy/scripts/static-release.py pack wao-web/dist "$WAO_RUN_TEMP/wao-static.tgz" "$WAO_GIT_SHA" || return
  python3 deploy/scripts/static-release.py install "$WAO_RUN_TEMP/wao-static.tgz" \
    "${JENKINS_HOME:-/var/lib/jenkins}/wao-static/releases" "$WAO_GIT_SHA" || return
  stage_cloud_static
}

activate_cloud() {
  retry_remote '静态路由清单' ssh -o ConnectTimeout=15 wao-cloud \
    "python3 /tmp/publish-static-routing.py /etc/wao-public-edge/routing.json '$WAO_GIT_SHA'" || return
  retry_remote '云端 Gateway 配置' ssh -o ConnectTimeout=15 wao-cloud \
    'WAO_NGINX_RENDER_OUTPUT=/tmp/wao-nginx.conf sh /opt/wao/deploy/scripts/render-nginx-config.sh' || return
  retry_remote '云端证书' ssh -o ConnectTimeout=15 wao-cloud \
    "/bin/sh /tmp/ensure-jumpserver-certificate.sh $CLOUD_HOST" || return
  retry_remote '云端静态发布' ssh -o ConnectTimeout=15 wao-cloud "
    set -eu
    final=/etc/nginx/conf.d/wao.wayuwa.cn.conf
    jms_final=/etc/nginx/conf.d/jms.wayuwa.cn.conf
    previous=\$(mktemp)
    jms_previous=\$(mktemp)
    jms_had_previous=false
    cp \"\$final\" \"\$previous\"
    if [ -f \"\$jms_final\" ]; then cp \"\$jms_final\" \"\$jms_previous\"; jms_had_previous=true; fi
    install -m 0644 /tmp/wao-nginx.conf \"\$final\"
    install -m 0644 /tmp/jms.wayuwa.cn.conf \"\$jms_final\"
    if ! nginx -t; then
      cp \"\$previous\" \"\$final\"
      if [ \"\$jms_had_previous\" = true ]; then cp \"\$jms_previous\" \"\$jms_final\"; else rm -f \"\$jms_final\"; fi
      rm -f \"\$previous\" \"\$jms_previous\" /tmp/wao-nginx.conf
      nginx -t
      exit 1
    fi
    rm -f \"\$previous\" \"\$jms_previous\" /tmp/wao-nginx.conf
    install -d -m 0755 /opt/wao/deploy/nginx /opt/wao/deploy/scripts
    install -m 0644 /tmp/wao.wayuwa.cn.conf.template /opt/wao/deploy/nginx/wao.wayuwa.cn.conf.template
    install -m 0644 /tmp/jms.wayuwa.cn.conf /opt/wao/deploy/nginx/jms.wayuwa.cn.conf
    install -m 0755 /tmp/activate-nginx-config.sh /opt/wao/deploy/scripts/activate-nginx-config.sh
    install -m 0755 /tmp/render-nginx-config.sh /opt/wao/deploy/scripts/render-nginx-config.sh
    install -m 0755 /tmp/resolve-gateway-ipv4.sh /opt/wao/deploy/scripts/resolve-gateway-ipv4.sh
    install -m 0755 /tmp/ensure-jumpserver-certificate.sh /opt/wao/deploy/scripts/ensure-jumpserver-certificate.sh
    rm -f /tmp/wao.wayuwa.cn.conf.template /tmp/activate-nginx-config.sh \
      /tmp/render-nginx-config.sh /tmp/resolve-gateway-ipv4.sh /tmp/jms.wayuwa.cn.conf \
      /tmp/ensure-jumpserver-certificate.sh
    printf '%s\\n' 'SHELL=/bin/sh' 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' \\
      '* * * * * root /opt/wao/deploy/scripts/activate-nginx-config.sh >/dev/null 2>&1' \\
      >/etc/cron.d/wao-nginx-ddns-reload
    chmod 0644 /etc/cron.d/wao-nginx-ddns-reload
    install -d /var/www/wao/dist
    release_dir=/var/www/wao/releases/'$WAO_GIT_SHA'
    test -f \"\$release_dir/index.html\"
    find \"\$release_dir\" -mindepth 1 -maxdepth 1 ! -name index.html \\
      ! -name release-manifest.json -exec cp -a -- {} /var/www/wao/dist/ \\;
    install -m 0644 \"\$release_dir/index.html\" /var/www/wao/dist/.index.html.next
    mv -f /var/www/wao/dist/.index.html.next /var/www/wao/dist/index.html
    systemctl reload nginx
    rm -f /tmp/publish-static-routing.py /tmp/static-release.py /tmp/wao-static.tgz
  "
}

gateway_ready_from_cloud() {
  ssh -o ConnectTimeout=15 wao-cloud '
    set -eu
    host=linux2.wayuwa.cn
    address="$(sh /opt/wao/deploy/scripts/resolve-gateway-ipv4.sh "$host")"
    response="$(curl -4 -kfsS --max-time 8 --resolve "$host:9000:$address" \
      "https://$host:9000/api/edge/ready")"
    printf "%s" "$response" | grep -Eq "\"code\"[[:space:]]*:[[:space:]]*0"
    printf "%s" "$response" | grep -Eq "\"protocol\"[[:space:]]*:[[:space:]]*1"
  '
}

public_ready() {
  local response
  response="$(curl --fail --silent --show-error --max-time 8 https://wao.wayuwa.cn/api/edge/ready)" || return 1
  printf '%s' "$response" | grep -Eq '"code"[[:space:]]*:[[:space:]]*0' &&
    printf '%s' "$response" | grep -Eq '"protocol"[[:space:]]*:[[:space:]]*1'
}

wait_until_ready() {
  local attempt=1
  until "$@"; do
    [ "$attempt" -lt 10 ] || return 1
    attempt=$((attempt + 1))
    sleep 2
  done
}

wait_until_stably_ready() {
  local attempt=1 consecutive=0
  while [ "$attempt" -le 12 ]; do
    if "$@"; then
      consecutive=$((consecutive + 1))
      [ "$consecutive" -lt 3 ] || return 0
    else
      consecutive=0
    fi
    [ "$attempt" -lt 12 ] || break
    attempt=$((attempt + 1))
    sleep 2
  done
  return 1
}

availability_pid=''
availability_stop="$WAO_RUN_TEMP/wao-availability.stop"
availability_failures="$WAO_RUN_TEMP/wao-availability.failures"
: >"$availability_failures"
rm -f "$availability_stop"

start_availability_monitor() {
  rm -f "$availability_stop"
  (
    while [ ! -f "$availability_stop" ]; do
      if ! public_ready; then
        date -u '+%Y-%m-%dT%H:%M:%SZ public /api/edge/ready unavailable' >>"$availability_failures"
      fi
      sleep 2
    done
  ) &
  availability_pid=$!
}

finish_single_node_restart_window() {
  local unavailable_samples
  stop_availability_monitor
  unavailable_samples="$(wc -l <"$availability_failures" | tr -d '[:space:]')"
  printf 'Linux2 单节点原地重启期间公共入口不可用采样 %s 次；已恢复连续就绪，后续发布继续严格监测。\n' \
    "$unavailable_samples"
  : >"$availability_failures"
  start_availability_monitor
}

stop_availability_monitor() {
  if [ -n "$availability_pid" ]; then
    touch "$availability_stop"
    wait "$availability_pid" || true
    availability_pid=''
  fi
}

mark_deploy_window() {
  ssh -o ConnectTimeout=15 wao-cloud \
    'install -d -m 0755 /run/wao-nginx-upstreams && touch /run/wao-nginx-upstreams/deploying'
}

cleanup_rollout() {
  stop_availability_monitor
  mark_deploy_window >/dev/null 2>&1 || true
  cleanup_deploy_inputs
}

publish_release() {
  if ! activate_cloud >cloud.log 2>&1; then
    set_status cloud_status failed
    set_status cloud_detail '云端静态资源或 Nginx 发布失败'
    cat cloud.log
    return 1
  fi
  set_status cloud_status success
  set_status cloud_detail "$WAO_GIT_SHA"
  cat cloud.log
  if ! wait_until_stably_ready public_ready; then
    set_status verification_status failed
    set_status verification_detail '公共入口数据库就绪检查失败'
    return 1
  fi
  stop_availability_monitor
  if [ -s "$availability_failures" ]; then
    cat "$availability_failures"
    set_status verification_status failed
    set_status verification_detail '发布期间公共入口出现数据库就绪失败采样'
    return 1
  fi
  set_status verification_status success
  set_status verification_detail 'Linux2、云端静态制品与公共入口检查通过'
  set_status publish_completed_at "$(TZ=Asia/Shanghai date '+%Y-%m-%d %H:%M:%S')"
}

deploy_browser_only() {
  local plan
  plan="$(sh deploy/jenkins/ci-change-plan.sh "$WAO_CHANGE_BASE" "$WAO_GIT_SHA")" || return 1
  printf '%s\n' "$plan" | grep -qx 'WAO_WEB_ONLY=true' || return 1
  prepare_cloud_routing || return 1
  wait_until_ready gateway_ready_from_cloud || return 1
  start_availability_monitor
  stage_cloud || { set_status cloud_status failed; set_status cloud_detail '云端静态制品预传失败'; return 1; }
  if ! publish_release; then return 1; fi
  set_status linux2_status success
  set_status linux2_detail '保留现有 Linux2 后端版本'
}

sync_jumpserver=false
batch_changed '^deploy/jumpserver/' && sync_jumpserver=true
sync_infrastructure=false
batch_changed '^deploy/infrastructure/retire-overview.sh$' && sync_infrastructure=true

if [ "${WAO_WEB_ONLY:-false}" = true ]; then
  trap 'stop_availability_monitor; cleanup_deploy_inputs' EXIT HUP INT TERM
  deploy_browser_only
  exit $?
fi

prepare_cloud_routing || exit 1
if ! wait_until_ready gateway_ready_from_cloud; then
  echo 'Linux2 Gateway 未通过数据库就绪检查；拒绝原地发布。' >&2
  set_status linux2_status failed
  set_status linux2_detail '发布前健康检查未通过'
  exit 1
fi
if ! wait_until_ready public_ready; then
  echo '公共 WAO 入口未通过数据库就绪检查；拒绝发布。' >&2
  set_status verification_status failed
  set_status verification_detail '发布前公共入口健康检查未通过'
  exit 1
fi

trap cleanup_rollout EXIT HUP INT TERM
mark_deploy_window || exit 1
if ! install_linux2_deploy_helper; then
  set_status linux2_status failed
  set_status linux2_detail '无法安装本次发布的 Linux2 部署助手'
  exit 1
fi

# Prepare immutable web assets before restarting the only application node.
if ! stage_cloud >cloud-stage.log 2>&1; then
  set_status cloud_status failed
  set_status cloud_detail '云端静态制品预传失败'
  cat cloud-stage.log
  exit 1
fi

start_availability_monitor
if ! retry_linux2_deploy linux2.log; then
  set_status linux2_status failed
  set_status linux2_detail "$(tail -n 1 linux2.log 2>/dev/null)"
  cat linux2.log
  exit 1
fi
cat linux2.log
if ! wait_until_ready gateway_ready_from_cloud; then
  set_status linux2_status failed
  set_status linux2_detail 'Linux2 部署后 Gateway 就绪检查失败'
  exit 1
fi
if ! wait_until_stably_ready public_ready; then
  set_status verification_status failed
  set_status verification_detail 'Linux2 重启后公共入口未恢复稳定就绪'
  exit 1
fi
finish_single_node_restart_window
set_status linux2_status success
set_status linux2_detail "$WAO_GIT_SHA"
if ! retire_cloud_sentinel; then
  set_status linux2_status failed
  set_status linux2_detail 'Linux2 已更新，但云端旧 Redis Sentinel 未能停用'
  exit 1
fi

if [ "$sync_jumpserver" = true ]; then
  # Linux2 deployment may create or rotate the persistent JumpServer key.
  # Publish the resulting public key to the cloud only after that deployment,
  # so verification uses the same key on both assets.
  if ! provision_jumpserver_asset_access; then
    set_status linux2_status failed
    set_status linux2_detail 'JumpServer 云入口 SSH 授权失败'
    exit 1
  fi
  jumpserver_verify_log="$WAO_RUN_TEMP/jumpserver-verify.log"
  if ! verify_jumpserver_assets >"$jumpserver_verify_log" 2>&1; then
    cat "$jumpserver_verify_log"
    set_status linux2_status failed
    set_status linux2_detail "JumpServer Linux2/云入口验收失败：$(tail -n 1 "$jumpserver_verify_log" | tr '\r\n=' '   ' | cut -c1-300)"
    exit 1
  fi
  cat "$jumpserver_verify_log"
fi

publish_release || exit 1
trap - EXIT
mark_deploy_window || true
cleanup_deploy_inputs
