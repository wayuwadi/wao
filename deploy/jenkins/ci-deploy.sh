#!/usr/bin/env bash
set -u

: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"
: "${WAO_RUN_TEMP:?WAO_RUN_TEMP is required}"
: "${WAO_MASTER_PROJECT_ROOT:?WAO_MASTER_PROJECT_ROOT is required}"
: "${WINDOWS_SSH_KEY:?WINDOWS_SSH_KEY is required}"
: "${WINDOWS_KNOWN_HOSTS:?WINDOWS_KNOWN_HOSTS is required}"
: "${SLAVE_USER:?SLAVE_USER is required}"
: "${CLOUD_SSH_KEY:?CLOUD_SSH_KEY is required}"
: "${CLOUD_KNOWN_HOSTS:?CLOUD_KNOWN_HOSTS is required}"
: "${CLOUD_USER:?CLOUD_USER is required}"

BOOTSTRAP_WINDOWS="${BOOTSTRAP_WINDOWS:-false}"
ALLOW_WINDOWS_OFFLINE="${ALLOW_WINDOWS_OFFLINE:-false}"
WAO_BUILD_ATTEMPT="${WAO_BUILD_ATTEMPT:-1}"
export BOOTSTRAP_WINDOWS ALLOW_WINDOWS_OFFLINE WAO_BUILD_ATTEMPT

umask 077
install -d "$WAO_RUN_TEMP"
test -f "$WAO_RUN_TEMP/wao-release.tgz"
test -f "$WAO_RUN_TEMP/wao-release.zip"
test -f "$WAO_RUN_TEMP/wao-images.tar.gz"
test -f "$WAO_MASTER_PROJECT_ROOT/deploy/.env"
test -f "$WAO_MASTER_PROJECT_ROOT/deploy/.env.secrets"
test -f "$WAO_MASTER_PROJECT_ROOT/deploy/certs/gateway.p12"

ln -sf "$WAO_RUN_TEMP/wao-release.tgz" wao-release.tgz
ln -sf "$WAO_RUN_TEMP/wao-release.zip" wao-release.zip
ln -sf "$WAO_RUN_TEMP/wao-images.tar.gz" wao-images.tar.gz
install -m 0640 "$WAO_MASTER_PROJECT_ROOT/deploy/.env.secrets" wao-secrets.env
install -m 0640 "$WAO_MASTER_PROJECT_ROOT/deploy/.env" wao-shared.env
install -m 0640 "$WAO_MASTER_PROJECT_ROOT/deploy/certs/gateway.p12" wao-gateway.p12

if [ "$BOOTSTRAP_WINDOWS" = true ]; then
  WAO_PROJECT_ROOT="$WAO_MASTER_PROJECT_ROOT" \
    sh deploy/scripts/create-mysql-snapshot.sh wao-bootstrap.sql.gz
fi

WAO_SSH_CONFIG="$WAO_RUN_TEMP/ssh_config"
cat >"$WAO_SSH_CONFIG" <<EOF
Host wao-windows
  HostName $SLAVE_HOST
  User $SLAVE_USER
  IdentityFile $WINDOWS_SSH_KEY
  IdentitiesOnly yes
  UserKnownHostsFile $WINDOWS_KNOWN_HOSTS
  StrictHostKeyChecking yes
Host wao-cloud
  HostName $CLOUD_HOST
  User $CLOUD_USER
  IdentityFile $CLOUD_SSH_KEY
  IdentitiesOnly yes
  UserKnownHostsFile $CLOUD_KNOWN_HOSTS
  StrictHostKeyChecking yes
EOF
chmod 0600 "$WAO_SSH_CONFIG"

cleanup_deploy_inputs() {
  rm -f -- wao-release.tgz wao-release.zip wao-images.tar.gz \
    wao-secrets.env wao-shared.env wao-gateway.p12 wao-bootstrap.sql.gz
}
trap cleanup_deploy_inputs EXIT HUP INT TERM

set -u
status_file="$WAO_RUN_TEMP/wao-deploy-status.env"
printf '%s\n' \
  'windows_status=not_started' 'windows_attempts=0' 'windows_detail=not_started' \
  'linux_status=not_started' 'linux_attempts=0' 'linux_detail=not_started' \
  'cloud_status=not_started' 'verification_status=not_started' >"$status_file"
slave_deployed=false
master_deployed=false
master_drained=false
schema_quiesced=false
schema_rollout_complete=false
linux_only_schema_rollout=false
safe_slave_reachable=false
windows_available=true
safe_schema_rollout=false

set_status() {
  key="$1"
  value="$(printf '%s' "$2" | tr '\r\n=' '   ' | cut -c1-500)"
  sed -i "/^${key}=/d" "$status_file"
  printf '%s=%s\n' "$key" "$value" >>"$status_file"
}

retry_remote() {
  target_label="$1"
  shift
  attempt=1
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

windows_ssh() {
  command ssh -F "$WAO_SSH_CONFIG" "$@"
}

windows_scp() {
  command scp -F "$WAO_SSH_CONFIG" "$@"
}

# After the Linux-local calls below are handled explicitly, every plain SSH/SCP
# operation in this script targets the cloud alias from the strict config.
ssh() { command ssh -F "$WAO_SSH_CONFIG" "$@"; }
scp() { command scp -F "$WAO_SSH_CONFIG" "$@"; }

retry_node_deploy() {
  label="$1"
  deploy_function="$2"
  log_file="$3"
  : >"$log_file"
  node_attempt=1
  while [ "$node_attempt" -le 3 ]; do
    printf '===== %s deployment attempt %s/3 =====\n' "$label" "$node_attempt" >>"$log_file"
    if "$deploy_function" >>"$log_file" 2>&1; then
      NODE_DEPLOY_ATTEMPTS="$node_attempt"
      return 0
    fi
    if [ "$node_attempt" -lt 3 ]; then
      printf '%s deployment failed; retrying in %s seconds.\n' "$label" "$((node_attempt * 15))" >>"$log_file"
      sleep "$((node_attempt * 15))"
    fi
    node_attempt=$((node_attempt + 1))
  done
  NODE_DEPLOY_ATTEMPTS=3
  return 1
}

deploy_master() {
  sudo -n -u wadi /usr/local/lib/wao-jenkins/deploy-master-local.sh \
    "$WAO_MASTER_PROJECT_ROOT" wao-release.tgz wao-secrets.env "$WAO_GIT_SHA" \
    "$SLAVE_USER" "$CLOUD_USER" "$SLAVE_HOST" "$CLOUD_HOST"
}

install_master_deploy_helper() {
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
  public_key="$WAO_MASTER_PROJECT_ROOT/deploy/.jumpserver-asset-key.pub"
  test -s "$public_key"

  retry_remote "Windows 从节点" windows_scp -o ConnectTimeout=15 \
    "$public_key" deploy/jumpserver/install-public-key.ps1 \
    "wao-windows:D:/deploy/" || return
  retry_remote "Windows 从节点" windows_ssh -o ConnectTimeout=15 "wao-windows" \
    'powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\deploy\install-public-key.ps1 -PublicKeyPath D:\deploy\.jumpserver-asset-key.pub' || return

  retry_remote "云前端" scp -o ConnectTimeout=15 \
    "$public_key" deploy/jumpserver/install-public-key.sh \
    "wao-cloud:/tmp/" || return
  retry_remote "云前端" ssh -o ConnectTimeout=15 "wao-cloud" \
    'sh /tmp/install-public-key.sh /tmp/.jumpserver-asset-key.pub' || return
}

verify_jumpserver_assets() {
  sudo -n sh "$WAO_MASTER_PROJECT_ROOT/deploy/jumpserver/verify-assets.sh"
}

deploy_slave() {
  release_suffix="${BUILD_NUMBER}-${WAO_BUILD_ATTEMPT:-1}"
  remote_release="wao-release-${release_suffix}.zip"
  remote_secrets="wao-secrets-${release_suffix}.env"
  remote_shared_env="wao-shared-${release_suffix}.env"
  remote_certificate="wao-gateway-${release_suffix}.p12"
  remote_images="wao-images-${release_suffix}.tar.gz"
  remote_snapshot="wao-bootstrap-${release_suffix}.sql.gz"
  remote_installer="install-release-windows-${release_suffix}.ps1"
  retry_remote "Windows 从节点" windows_scp -o ConnectTimeout=15 \
    wao-release.zip wao-secrets.env wao-shared.env wao-gateway.p12 wao-images.tar.gz \
    "wao-windows:D:/deploy/" || return
  retry_remote "Windows 从节点" windows_scp -o ConnectTimeout=15 \
    deploy/scripts/install-release-windows.ps1 \
    "wao-windows:D:/deploy/$remote_installer" || return
  retry_remote "Windows 从节点" windows_ssh -o ConnectTimeout=15 "wao-windows" \
    "powershell.exe -NoProfile -ExecutionPolicy Bypass -Command \"Move-Item -Force D:\\deploy\\wao-release.zip D:\\deploy\\$remote_release; Move-Item -Force D:\\deploy\\wao-secrets.env D:\\deploy\\$remote_secrets; Move-Item -Force D:\\deploy\\wao-shared.env D:\\deploy\\$remote_shared_env; Move-Item -Force D:\\deploy\\wao-gateway.p12 D:\\deploy\\$remote_certificate; Move-Item -Force D:\\deploy\\wao-images.tar.gz D:\\deploy\\$remote_images\"" || return
  retry_remote "Windows 从节点" windows_ssh -o ConnectTimeout=15 "wao-windows" \
    "powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\\deploy\\$remote_installer -ArchivePath D:\\deploy\\$remote_release -PrivateEnvPath D:\\deploy\\$remote_secrets -SharedEnvPath D:\\deploy\\$remote_shared_env -GatewayCertificatePath D:\\deploy\\$remote_certificate -ImageArchivePath D:\\deploy\\$remote_images -ImageTag $WAO_GIT_SHA" || return
  if [ "$BOOTSTRAP_WINDOWS" = true ]; then
    test -s wao-bootstrap.sql.gz || return 1
    retry_remote "Windows 从节点" windows_scp -o ConnectTimeout=15 wao-bootstrap.sql.gz \
      "wao-windows:D:/deploy/$remote_snapshot" || return
    retry_remote "Windows 从节点" windows_ssh -o ConnectTimeout=15 "wao-windows" \
      "powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\\deploy\\wao\\deploy\\scripts\\bootstrap-windows-slave.ps1 -SnapshotPath D:\\deploy\\$remote_snapshot" || return
  fi
  retry_remote "Windows 从节点" windows_ssh -o ConnectTimeout=15 "wao-windows" \
    "wsl.exe -d Ubuntu -u root -- sh /mnt/d/deploy/wao/deploy/scripts/validate-deployment-node.sh slave $WAO_GIT_SHA" || return
  slave_deployed=true
}

prepare_cloud_routing() {
  retry_remote "云前端路由" scp -o ConnectTimeout=15 \
    deploy/nginx/wao.wayuwa.cn.conf.template \
    deploy/nginx/jms.wayuwa.cn.conf \
    deploy/scripts/activate-nginx-config.sh deploy/scripts/render-nginx-config.sh \
    deploy/scripts/ensure-jumpserver-certificate.sh \
    deploy/scripts/set-nginx-upstream-state.sh \
    "wao-cloud:/tmp/" || return
  retry_remote "云前端路由" ssh -o ConnectTimeout=15 "wao-cloud" '
    set -eu
    install -d -m 0755 /opt/wao/deploy/nginx /opt/wao/deploy/scripts
    install -m 0644 /tmp/wao.wayuwa.cn.conf.template /opt/wao/deploy/nginx/wao.wayuwa.cn.conf.template
    install -m 0644 /tmp/jms.wayuwa.cn.conf /opt/wao/deploy/nginx/jms.wayuwa.cn.conf
    install -m 0755 /tmp/activate-nginx-config.sh /opt/wao/deploy/scripts/activate-nginx-config.sh
    install -m 0755 /tmp/render-nginx-config.sh /opt/wao/deploy/scripts/render-nginx-config.sh
    install -m 0755 /tmp/set-nginx-upstream-state.sh /opt/wao/deploy/scripts/set-nginx-upstream-state.sh
    install -m 0755 /tmp/ensure-jumpserver-certificate.sh /opt/wao/deploy/scripts/ensure-jumpserver-certificate.sh
    # 新主配置已经接管 /game 与 /insight；首次摘流也会渲染它，必须先移除旧片段，
    # 否则 nginx -t 会因 duplicate location 拒绝摘流，永远到不了 deploy_cloud 的清理步骤。
    install -d -m 0755 /etc/nginx/wao-external-apps.d
    rm -f /etc/nginx/wao-external-apps.d/wao-game.conf \
      /etc/nginx/wao-external-apps.d/wao-insight.conf
  '
}

deploy_cloud() {
  resolve_ipv4() {
    getent ahostsv4 "$1" | awk '$2 == "STREAM" { print $1; exit }'
  }
  master_gateway_ipv4="$(resolve_ipv4 "$MASTER_HOST")"
  slave_gateway_ipv4="$(resolve_ipv4 "$SLAVE_HOST")"
  test -n "$master_gateway_ipv4"
  test -n "$slave_gateway_ipv4"
  MASTER_GATEWAY_IPV4="$master_gateway_ipv4" SLAVE_GATEWAY_IPV4="$slave_gateway_ipv4" \
    WAO_NGINX_RENDER_OUTPUT=wao-nginx.conf sh deploy/scripts/render-nginx-config.sh
  retry_remote "云前端" ssh -o ConnectTimeout=15 "wao-cloud" '
    set -eu
    install -d /tmp/wao-dist /tmp/wao-agent-dist
    find /tmp/wao-dist -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
    find /tmp/wao-agent-dist -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  ' || return
  retry_remote "云前端" scp -o ConnectTimeout=15 -r wao-web/dist/. "wao-cloud:/tmp/wao-dist/" || return
  retry_remote "云前端" scp -o ConnectTimeout=15 -r agent-web/dist/. "wao-cloud:/tmp/wao-agent-dist/" || return
  retry_remote "云前端" scp -o ConnectTimeout=15 wao-nginx.conf "wao-cloud:/tmp/wao-nginx.conf" || return
  retry_remote "云前端" scp -o ConnectTimeout=15 \
    deploy/nginx/wao.wayuwa.cn.conf.template \
    deploy/nginx/jms.wayuwa.cn.conf \
    deploy/scripts/activate-nginx-config.sh deploy/scripts/render-nginx-config.sh \
    deploy/scripts/ensure-jumpserver-certificate.sh \
    deploy/scripts/set-nginx-upstream-state.sh \
    "wao-cloud:/tmp/" || return
  retry_remote "云前端" ssh -o ConnectTimeout=15 "wao-cloud" \
    "/bin/sh /tmp/ensure-jumpserver-certificate.sh $CLOUD_HOST" || return
  retry_remote "云前端" ssh -o ConnectTimeout=15 "wao-cloud" '
    set -eu
    final=/etc/nginx/conf.d/wao.wayuwa.cn.conf
    jms_final=/etc/nginx/conf.d/jms.wayuwa.cn.conf
    previous="$(mktemp)"
    jms_previous="$(mktemp)"
    jms_had_previous=false
    retired_routes_backup="$(mktemp -d)"
    cp "$final" "$previous"
    if [ -f "$jms_final" ]; then
      cp "$jms_final" "$jms_previous"
      jms_had_previous=true
    fi
    # 真正独立的第三方应用继续使用此目录；已经并入或取消的内部应用精确退役。
    install -d -m 0755 /etc/nginx/wao-external-apps.d
    for route in wao-game.conf wao-insight.conf; do
      if [ -f "/etc/nginx/wao-external-apps.d/$route" ]; then
        cp "/etc/nginx/wao-external-apps.d/$route" "$retired_routes_backup/$route"
      fi
    done
    rm -f /etc/nginx/wao-external-apps.d/wao-game.conf /etc/nginx/wao-external-apps.d/wao-insight.conf
    install -m 0644 /tmp/wao-nginx.conf "$final"
    install -m 0644 /tmp/jms.wayuwa.cn.conf "$jms_final"
    if ! nginx -t; then
      cp "$previous" "$final"
      if [ "$jms_had_previous" = true ]; then
        cp "$jms_previous" "$jms_final"
      else
        rm -f "$jms_final"
      fi
      for route in "$retired_routes_backup"/*; do
        [ -f "$route" ] || continue
        cp "$route" /etc/nginx/wao-external-apps.d/
      done
      rm -f "$retired_routes_backup/wao-game.conf" "$retired_routes_backup/wao-insight.conf"
      rmdir "$retired_routes_backup"
      rm -f "$previous" "$jms_previous" /tmp/wao-nginx.conf
      nginx -t
      exit 1
    fi
    rm -f "$retired_routes_backup/wao-game.conf" "$retired_routes_backup/wao-insight.conf"
    rmdir "$retired_routes_backup"
    rm -f "$previous" "$jms_previous" /tmp/wao-nginx.conf
    install -d -m 0755 /opt/wao/deploy/nginx /opt/wao/deploy/scripts
    install -m 0644 /tmp/wao.wayuwa.cn.conf.template /opt/wao/deploy/nginx/wao.wayuwa.cn.conf.template
    install -m 0644 /tmp/jms.wayuwa.cn.conf /opt/wao/deploy/nginx/jms.wayuwa.cn.conf
    install -m 0755 /tmp/activate-nginx-config.sh /opt/wao/deploy/scripts/activate-nginx-config.sh
    install -m 0755 /tmp/render-nginx-config.sh /opt/wao/deploy/scripts/render-nginx-config.sh
    install -m 0755 /tmp/set-nginx-upstream-state.sh /opt/wao/deploy/scripts/set-nginx-upstream-state.sh
    install -m 0755 /tmp/ensure-jumpserver-certificate.sh /opt/wao/deploy/scripts/ensure-jumpserver-certificate.sh
    rm -f /tmp/wao.wayuwa.cn.conf.template /tmp/activate-nginx-config.sh \
      /tmp/render-nginx-config.sh /tmp/set-nginx-upstream-state.sh \
      /tmp/jms.wayuwa.cn.conf /tmp/ensure-jumpserver-certificate.sh
    printf "%s\n" \
      "SHELL=/bin/sh" \
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      "* * * * * root /opt/wao/deploy/scripts/activate-nginx-config.sh >/dev/null 2>&1" \
      >/etc/cron.d/wao-nginx-ddns-reload
    chmod 0644 /etc/cron.d/wao-nginx-ddns-reload
    install -d /var/www/wao/dist
    find /var/www/wao/dist -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
    cp -a /tmp/wao-dist/. /var/www/wao/dist/
    install -d /var/www/wao-agent/dist
    find /var/www/wao-agent/dist -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
    cp -a /tmp/wao-agent-dist/. /var/www/wao-agent/dist/
    systemctl reload nginx
    for legacy_dist in /var/www/wao-game/dist /var/www/wao-insight/dist; do
      if [ -d "$legacy_dist" ]; then
        find "$legacy_dist" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
      fi
    done
  '
}

gateway_ready_from_cloud() {
  gateway_host="$1"
  ssh -o ConnectTimeout=15 "wao-cloud" \
    "curl -4 -kfsS --max-time 8 https://$gateway_host:9000/api/hello | grep -q '\"code\":0'"
}

set_upstream_state() {
  node="$1"
  state="$2"
  retry_remote "云前端路由" ssh -o ConnectTimeout=15 "wao-cloud" \
    "/opt/wao/deploy/scripts/set-nginx-upstream-state.sh $node $state"
}

quiesce_master() {
  action="$1"
  sh deploy/scripts/quiesce-wao-server.sh master "$action" "$WAO_MASTER_PROJECT_ROOT"
}

quiesce_slave() {
  action="$1"
  retry_remote "Windows 从节点" windows_ssh -o ConnectTimeout=15 "wao-windows" \
    "powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\\deploy\\wao-quiesce-server.ps1 -Action $action"
}

restore_upstreams() {
  if [ "$slave_deployed" = true ]; then
    set_upstream_state slave up >/dev/null 2>&1 || true
  else
    set_upstream_state slave down >/dev/null 2>&1 || true
  fi
  if [ "$schema_quiesced" = true ] && [ "$schema_rollout_complete" != true ]; then
    if [ "$linux_only_schema_rollout" != true ]; then
      set_upstream_state master down >/dev/null 2>&1 || true
      return
    fi
  fi
  if [ "$master_deployed" = true ] || [ "$master_drained" = false ]; then
    set_upstream_state master up >/dev/null 2>&1 || true
  else
    set_upstream_state master down >/dev/null 2>&1 || true
  fi
}

availability_pid=''
availability_stop="$WAO_RUN_TEMP/wao-availability.stop"
availability_failures="$WAO_RUN_TEMP/wao-availability.failures"
: >"$availability_failures"
rm -f "$availability_stop"

start_availability_monitor() {
  (
    while [ ! -f "$availability_stop" ]; do
      if ! curl --fail --silent --show-error --max-time 8 --retry 2 --retry-delay 1 \
          --retry-all-errors https://wao.wayuwa.cn/api/hello >/dev/null; then
        date -u '+%Y-%m-%dT%H:%M:%SZ public /api/hello unavailable' >>"$availability_failures"
      fi
      sleep 2
    done
  ) &
  availability_pid=$!
}

stop_availability_monitor() {
  if [ -n "$availability_pid" ]; then
    touch "$availability_stop"
    wait "$availability_pid" || true
    availability_pid=''
  fi
}

cleanup_rollout() {
  stop_availability_monitor
  restore_upstreams
  cleanup_deploy_inputs
}

# 是否需要 V77 维护窗口以主库持久状态为准，不依赖单次 push 的文件列表。
# 这样 Jenkins 手工构建、失败重跑或人工中止后的下一次部署都会继续 fail-closed 流程。
rollout_status_output="$(quiesce_master rollout-status)" || exit 1
rollout_status="$(printf '%s\n' "$rollout_status_output" | tr -d '\r' | tail -n 1)"
case "$rollout_status" in
  complete) safe_schema_rollout=false ;;
  required) safe_schema_rollout=true ;;
  *) printf '无法识别 V77 发布状态：%s\n' "$rollout_status"; exit 1 ;;
esac

if ! prepare_cloud_routing >routing.log 2>&1; then
  printf '\n===== cloud routing =====\n'; cat routing.log
  exit 1
fi

trap cleanup_rollout EXIT

if ! windows_ssh -o BatchMode=yes -o ConnectTimeout=8 -o ConnectionAttempts=1 \
    "wao-windows" 'wsl.exe -d Ubuntu -u root -- docker info' >/dev/null; then
  set_upstream_state slave down || true
  if [ "$ALLOW_WINDOWS_OFFLINE" = true ]; then
    windows_available=false
    set_status windows_status skipped
    set_status windows_detail 'Windows 预检不可达；已显式授权 Linux-only 发布'
    echo 'Windows 从节点预检不可达；保持摘流并按显式授权继续发布 Linux 与云端。'
  else
    set_status windows_status failed
    set_status windows_detail 'SSH、WSL 或 Docker 预检失败'
    echo 'Windows 从节点严格预检失败；Linux 保持当前版本，本次发布失败。'
    exit 1
  fi
fi

if [ "$safe_schema_rollout" != true ] && [ "$windows_available" = true ]; then
  start_availability_monitor
fi

# V77 首次引入带版本的飞书归档写入。旧二进制不认识新栅栏，因此该次 push
# 用一次性维护窗口：先暂停定时同步、摘流并停掉所有在线旧 server，再允许新节点执行 migration。
# Windows 离线且成员显式授权时保持 slave down 和同步暂停，但继续交付 Linux；详见 ADR-100。
if [ "$safe_schema_rollout" = true ]; then
  echo '::notice title=V77 安全升级::暂停飞书定时同步并停止旧版 API 节点后再执行 migration。'
  if ! set_upstream_state slave down || ! set_upstream_state master down; then
    echo '无法为 V77 建立全量摘流窗口。'
    exit 1
  fi
  if [ "$windows_available" = true ]; then
    safe_slave_reachable=true
    retry_remote "Windows 从节点" windows_scp -o ConnectTimeout=15 \
      deploy/scripts/quiesce-wao-server.sh \
      "wao-windows:D:/deploy/wao-quiesce-server.sh" || exit 1
    retry_remote "Windows 从节点" windows_scp -o ConnectTimeout=15 \
      deploy/scripts/quiesce-wao-server-windows.ps1 \
      "wao-windows:D:/deploy/wao-quiesce-server.ps1" || exit 1
  else
    echo 'Windows 从节点离线；保持 slave 摘流和飞书同步暂停，本次继续升级 Linux 与云端。'
  fi
  if ! quiesce_master pause-feishu-sync; then
    echo '无法暂停飞书定时同步，V77 未开始。'
    exit 1
  fi
  if [ "$safe_slave_reachable" = true ] && [ "$BOOTSTRAP_WINDOWS" != true ] && ! quiesce_slave stop; then
    quiesce_master restore-feishu-sync || true
    echo '无法停止 Windows 旧版 server，V77 未开始。'
    exit 1
  fi
  if ! quiesce_master stop; then
    if [ "$safe_slave_reachable" = true ]; then quiesce_slave start || true; fi
    quiesce_master restore-feishu-sync || true
    echo '无法停止 Linux 旧版 server，V77 未开始。'
    exit 1
  fi
  schema_quiesced=true
fi

set_upstream_state slave down || exit 1
if [ "$safe_schema_rollout" != true ]; then
  curl --fail --silent --show-error --retry 3 --retry-delay 2 --retry-all-errors \
    https://wao.wayuwa.cn/api/hello | grep -q '"code":0'
fi

if [ "$windows_available" = true ]; then
  if ! retry_node_deploy Windows deploy_slave slave.log; then
    set_status windows_status failed
    set_status windows_attempts "$NODE_DEPLOY_ATTEMPTS"
    set_status windows_detail "$(tail -n 1 slave.log)"
    set_upstream_state slave down || true
    printf '\n===== Windows deployment =====\n'; cat slave.log
    echo 'Windows 从节点三次部署失败；Linux 保持当前版本，本次发布严格失败。'
    exit 1
  fi
  set_status windows_status success
  set_status windows_attempts "$NODE_DEPLOY_ATTEMPTS"
  set_status windows_detail "$WAO_GIT_SHA"
  if ! gateway_ready_from_cloud "$SLAVE_HOST"; then
    if [ "$ALLOW_WINDOWS_OFFLINE" = true ]; then
      windows_available=false
      slave_deployed=false
      set_status windows_status skipped
      set_status windows_detail 'Windows 已部署，但云节点无法直连 Gateway；保持摘流并继续 Linux-only 发布'
      set_upstream_state slave down || true
      echo 'Windows 已部署且节点内验证通过，但云节点无法直连 Gateway；按显式授权保持摘流并继续发布。'
    else
      set_status windows_status failed
      set_status windows_detail '云节点无法直连 Windows Gateway'
      set_upstream_state slave down || true
      exit 1
    fi
  else
    set_upstream_state slave up || exit 1
    printf '\n===== Windows deployment =====\n'; cat slave.log
  fi
fi

if ! install_master_deploy_helper; then
  set_status linux_status failed
  set_status linux_detail '无法安装本次发布的 Linux 部署助手'
  exit 1
fi

if [ "$safe_schema_rollout" != true ]; then
  set_upstream_state master down || exit 1
fi
master_drained=true
if [ "$windows_available" = true ]; then
  for probe in 1 2 3 4 5; do
    hello="$(curl --fail --silent --show-error --retry 3 --retry-delay 2 --retry-all-errors \
      "https://wao.wayuwa.cn/api/hello?windows-rollout-probe=$probe")"
    printf '%s' "$hello" | grep -q '"code":0'
  done
fi

if ! retry_node_deploy Linux deploy_master master.log; then
  set_status linux_status failed
  set_status linux_attempts "$NODE_DEPLOY_ATTEMPTS"
  set_status linux_detail "$(tail -n 1 master.log)"
  set_upstream_state master down || true
  printf '\n===== Linux deployment =====\n'; cat master.log
  echo 'Linux 主节点三次部署失败；Windows 继续承载请求，本次发布严格失败。'
  exit 1
fi
master_deployed=true
set_status linux_status success
set_status linux_attempts "$NODE_DEPLOY_ATTEMPTS"
set_status linux_detail "$WAO_GIT_SHA"

if [ "$safe_schema_rollout" = true ]; then
  if [ "$safe_slave_reachable" = true ]; then
    if ! quiesce_master complete-v77-rollout; then
      set_status linux_status failed
      set_status linux_detail 'V77 完成标记或飞书同步恢复失败'
      exit 1
    fi
    completed_status_output="$(quiesce_master rollout-status)" || exit 1
    completed_status="$(printf '%s\n' "$completed_status_output" | tr -d '\r' | tail -n 1)"
    if [ "$completed_status" != complete ]; then
      set_status linux_status failed
      set_status linux_detail 'V77 完成状态复核失败'
      exit 1
    fi
    schema_rollout_complete=true
  else
    linux_only_schema_rollout=true
    echo 'Linux 已单节点完成；Windows 继续保持 down，V77 完成标记和飞书同步保持暂停。'
  fi
fi
set_upstream_state master up || exit 1
printf '\n===== Linux deployment =====\n'; cat master.log

if ! provision_jumpserver_asset_access; then
  set_status linux_status failed
  set_status linux_detail 'JumpServer 专用资产公钥安装失败'
  exit 1
fi
if ! verify_jumpserver_assets; then
  set_status linux_status failed
  set_status linux_detail 'JumpServer 三节点 SSH 或资产登记验收失败'
  exit 1
fi

if ! deploy_cloud >cloud.log 2>&1; then
  set_status cloud_status failed
  printf '\n===== cloud =====\n'; cat cloud.log
  exit 1
fi
set_status cloud_status success
printf '\n===== cloud =====\n'; cat cloud.log

gateway_ready_from_cloud "$MASTER_HOST"
sh "$WAO_MASTER_PROJECT_ROOT/deploy/scripts/validate-deployment-node.sh" master "$WAO_GIT_SHA"
if [ "$windows_available" = true ]; then
  gateway_ready_from_cloud "$SLAVE_HOST"
  retry_remote "Windows 从节点" windows_ssh "wao-windows" \
    "wsl.exe -d Ubuntu -u root -- sh /mnt/d/deploy/wao/deploy/scripts/validate-deployment-node.sh slave $WAO_GIT_SHA"
fi

stop_availability_monitor
if [ -s "$availability_failures" ]; then
  set_status verification_status failed
  cat "$availability_failures"
  echo '滚动发布期间公共入口出现不可用采样。'
  exit 1
fi
set_status verification_status success
trap - EXIT
restore_upstreams
cleanup_deploy_inputs
