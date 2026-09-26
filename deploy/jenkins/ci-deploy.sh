#!/usr/bin/env bash
set -u

# Shared with clean Nacos backups: do not restart applications during its stop window.
# Legacy hosts without the installed maintenance lock keep their existing behavior.
if [[ -f /run/lock/wao-infra-maintenance.lock ]]; then
  exec 7>/run/lock/wao-infra-maintenance.lock || exit 1
  flock -s -w 600 7 || { echo '基础设施维护未结束，拒绝重启应用。'; exit 1; }
fi

: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"
: "${WAO_RUN_TEMP:?WAO_RUN_TEMP is required}"
: "${WAO_MASTER_PROJECT_ROOT:?WAO_MASTER_PROJECT_ROOT is required}"
: "${SLAVE_SSH_KEY:?SLAVE_SSH_KEY is required}"
: "${SLAVE_KNOWN_HOSTS:?SLAVE_KNOWN_HOSTS is required}"
: "${SLAVE_USER:?SLAVE_USER is required}"
: "${SLAVE_SSH_HOST:?SLAVE_SSH_HOST is required}"
: "${SLAVE_SSH_PORT:?SLAVE_SSH_PORT is required}"
: "${CLOUD_SSH_KEY:?CLOUD_SSH_KEY is required}"
: "${CLOUD_KNOWN_HOSTS:?CLOUD_KNOWN_HOSTS is required}"
: "${CLOUD_USER:?CLOUD_USER is required}"

BOOTSTRAP_SLAVE="${BOOTSTRAP_SLAVE:-false}"
ALLOW_SLAVE_OFFLINE="${ALLOW_SLAVE_OFFLINE:-false}"
WAO_BUILD_ATTEMPT="${WAO_BUILD_ATTEMPT:-1}"
export BOOTSTRAP_SLAVE ALLOW_SLAVE_OFFLINE WAO_BUILD_ATTEMPT

umask 077
install -d "$WAO_RUN_TEMP"
test -f "$WAO_RUN_TEMP/wao-release.tgz"
test -f "$WAO_MASTER_PROJECT_ROOT/deploy/.env"
test -f "$WAO_MASTER_PROJECT_ROOT/deploy/.env.secrets"
test -f "$WAO_MASTER_PROJECT_ROOT/deploy/certs/gateway.p12"

require_shared_env_line() {
  expected="$1"
  if ! grep -Fqx "$expected" "$WAO_MASTER_PROJECT_ROOT/deploy/.env"; then
    echo "生产共享环境缺少当前 Linux1/Linux2 拓扑：$expected"
    return 1
  fi
}

require_shared_env_line 'REDIS_MASTER_HOST=192.168.31.180' || exit 1
require_shared_env_line 'REDIS_SENTINEL_NODES=192.168.31.180:26379,192.168.2.180:26379,10.66.0.1:26379' || exit 1
# 4 is the staged no-failover gate; 2 is enabled only after client acceptance.
grep -Eq '^REDIS_HA_QUORUM=(2|4)$' "$WAO_MASTER_PROJECT_ROOT/deploy/.env" || exit 1
require_shared_env_line 'MASTER_PUBLIC_HOST=linux2.wayuwa.cn' || exit 1
require_shared_env_line 'SLAVE_PUBLIC_HOST=linux1.wayuwa.cn' || exit 1

ln -sf "$WAO_RUN_TEMP/wao-release.tgz" wao-release.tgz
install -m 0640 "$WAO_MASTER_PROJECT_ROOT/deploy/.env.secrets" wao-secrets.env
install -m 0640 "$WAO_MASTER_PROJECT_ROOT/deploy/.env" wao-shared.env
install -m 0640 "$WAO_MASTER_PROJECT_ROOT/deploy/certs/gateway.p12" wao-gateway.p12

if [ "$BOOTSTRAP_SLAVE" = true ]; then
  WAO_PROJECT_ROOT="$WAO_MASTER_PROJECT_ROOT" \
    sh deploy/scripts/create-mysql-snapshot.sh wao-bootstrap.sql.gz
fi

WAO_SSH_CONFIG="$WAO_RUN_TEMP/ssh_config"
cat >"$WAO_SSH_CONFIG" <<EOF
Host wao-slave
  HostName $SLAVE_SSH_HOST
  Port $SLAVE_SSH_PORT
  HostKeyAlias $SLAVE_HOST
  User $SLAVE_USER
  IdentityFile $SLAVE_SSH_KEY
  IdentitiesOnly yes
  UserKnownHostsFile $SLAVE_KNOWN_HOSTS
  StrictHostKeyChecking yes
  ServerAliveInterval 30
  ServerAliveCountMax 6
Host wao-cloud
  HostName $CLOUD_HOST
  User $CLOUD_USER
  IdentityFile $CLOUD_SSH_KEY
  IdentitiesOnly yes
  UserKnownHostsFile $CLOUD_KNOWN_HOSTS
  StrictHostKeyChecking yes
  # 一次发布对云端有二十余次 SSH/SCP；复用同一条连接省去每次握手。
  # Linux1 反向隧道会断线重连，不复用，避免一条失效主连接拖垮后续命令。
  ControlMaster auto
  ControlPath /tmp/wao-ci-%C
  ControlPersist 120
EOF
chmod 0600 "$WAO_SSH_CONFIG"

cleanup_deploy_inputs() {
  command ssh -F "$WAO_SSH_CONFIG" -O exit wao-cloud >/dev/null 2>&1 || true
  rm -f -- wao-release.tgz \
    wao-secrets.env wao-shared.env wao-gateway.p12 wao-bootstrap.sql.gz
}
trap cleanup_deploy_inputs EXIT HUP INT TERM

set -u
status_file="$WAO_RUN_TEMP/wao-deploy-status.env"
printf '%s\n' \
  'slave_status=not_started' 'slave_attempts=0' 'slave_detail=not_started' \
  'master_status=not_started' 'master_attempts=0' 'master_detail=not_started' \
  'cloud_status=not_started' 'cloud_detail=not_started' \
  'verification_status=not_started' 'verification_detail=not_started' >"$status_file"
slave_available=true

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

slave_ssh() {
  command ssh -F "$WAO_SSH_CONFIG" "$@"
}

slave_scp() {
  command scp -F "$WAO_SSH_CONFIG" "$@"
}

slave_deploy_ssh() {
  # 包含最多 10 分钟的部署锁等待，并为镜像导入和容器重建保留余量；即使远端进程
  # 仍保持 SSH 连接，也不能让单次 Jenkins 尝试无限挂起。
  command timeout --foreground --signal=TERM 30m ssh -F "$WAO_SSH_CONFIG" "$@"
}

run_marked_slave_command() {
  local ssh_runner success_marker output_file status_file remote_command ssh_exit
  ssh_runner="$1"
  success_marker="$2"
  output_file="$3"
  remote_command="$4"
  status_file="$output_file.status"
  : >"$output_file"
  : >"$status_file"

  # Linux1's reverse tunnel can report a reset while the remote shell is
  # closing even though the command itself completed. Emit the verified Git
  # SHA before that close and treat only the exact marker as success.
  (
    set +e
    "$ssh_runner" -o ConnectTimeout=15 "wao-slave" \
      "$remote_command && printf '\\n%s\\n' '$success_marker' && sleep 2"
    printf '%s\n' "$?" >"$status_file"
  ) 2>&1 | tee "$output_file"
  ssh_exit="$(sed -n '1p' "$status_file")"
  rm -f -- "$status_file"
  case "$ssh_exit" in
    ''|*[!0-9]*) ssh_exit=255 ;;
  esac
  if grep -Fqx "$success_marker" "$output_file"; then
    if [ "$ssh_exit" -ne 0 ]; then
      echo 'Linux1 SSH 关闭状态异常，但远端目标版本成功标记已确认。'
    fi
    return 0
  fi
  if [ "$ssh_exit" -eq 0 ]; then
    return 1
  fi
  return "$ssh_exit"
}

run_slave_installer() {
  local remote_command installer_exit installer_marker verification_marker
  remote_command="$1"
  installer_marker="WAO_LINUX1_INSTALL_VERIFIED=$WAO_GIT_SHA"
  verification_marker="WAO_LINUX1_RELEASE_VERIFIED=$WAO_GIT_SHA"
  if run_marked_slave_command slave_deploy_ssh "$installer_marker" \
    "$WAO_RUN_TEMP/linux1-installer-output.log" "$remote_command"; then
    return 0
  else
    installer_exit="$?"
  fi

  # The Linux1 reverse tunnel can reconnect after the remote installer has
  # already committed the release. The installer deliberately deletes its
  # one-time inputs on exit, so rerunning the same command turns a completed
  # deployment into a misleading "Release input not found" failure. Verify
  # the desired immutable version instead; the outer node retry will upload a
  # fresh release only when this post-check also fails.
  echo 'Linux1 部署连接异常，正在按目标版本执行远端完成态验收。'
  if retry_remote "Linux1 部署完成态验收" run_marked_slave_command \
    slave_ssh "$verification_marker" "$WAO_RUN_TEMP/linux1-verification-output.log" \
    "cd \"\$HOME/deploy/wao\" && sh deploy/scripts/validate-deployment-node.sh slave '$WAO_GIT_SHA'"; then
    echo 'Linux1 部署连接虽已中断，但目标版本与节点健康检查均已通过。'
    return 0
  fi
  return "$installer_exit"
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
    printf '===== %s deployment attempt %s/3 =====\n' "$label" "$node_attempt" | tee -a "$log_file"
    # 节点安装、镜像导入可能持续数分钟。实时复制到 Jenkins 控制台，避免正常等待被误判为卡死；
    # 子 shell 的 pipefail 仍以部署函数的退出码决定是否重试。
    if (set -o pipefail; "$deploy_function" 2>&1 | tee -a "$log_file"); then
      NODE_DEPLOY_ATTEMPTS="$node_attempt"
      return 0
    fi
    if [ "$node_attempt" -lt 3 ]; then
      printf '%s deployment failed; retrying in %s seconds.\n' \
        "$label" "$((node_attempt * 15))" | tee -a "$log_file"
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
    "$SLAVE_USER" "$CLOUD_USER" "$SLAVE_SSH_HOST" "$CLOUD_HOST" "$SLAVE_SSH_PORT" \
    "$sync_jumpserver" "$sync_infrastructure"
}

# JumpServer 与基础设施清单与应用版本无关：只有本批次改到对应目录（或无法判定）时才重新同步验收。
batch_changed() {
  local changed
  [ "$BOOTSTRAP_SLAVE" != true ] || return 0
  changed="$(git diff --name-only "${WAO_CHANGE_BASE:-}" "$WAO_GIT_SHA" 2>/dev/null)" || return 0
  printf '%s\n' "$changed" | grep -Eq "$1"
}

install_master_deploy_helper() {
  if cmp -s deploy/jenkins/deploy-master-local.sh /usr/local/lib/wao-jenkins/deploy-master-local.sh; then
    return 0
  fi
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

  retry_remote "Linux1 从节点" slave_scp -o ConnectTimeout=15 \
    "$public_key" deploy/jumpserver/install-public-key.sh \
    "wao-slave:/tmp/" || return
  retry_remote "Linux1 从节点" slave_ssh -o ConnectTimeout=15 "wao-slave" \
    'sh /tmp/install-public-key.sh /tmp/.jumpserver-asset-key.pub' || return

  retry_remote "云前端" scp -o ConnectTimeout=15 \
    "$public_key" deploy/jumpserver/install-public-key.sh \
    "wao-cloud:/tmp/" || return
  retry_remote "云前端" ssh -o ConnectTimeout=15 "wao-cloud" \
    'sh /tmp/install-public-key.sh /tmp/.jumpserver-asset-key.pub' || return
}

verify_jumpserver_assets() {
  sudo -n -u wadi /usr/local/lib/wao-jenkins/deploy-master-local.sh \
    --verify-jumpserver-assets "$WAO_MASTER_PROJECT_ROOT"
}

prepare_slave_images() {
  local image image_id fingerprint content_tag content_tags='' present_report resolved_id
  local -a missing_images=()
  local manifest="$WAO_RUN_TEMP/wao-image-manifest.txt"
  local desired="$WAO_RUN_TEMP/wao-image-desired.txt"
  local archive="$WAO_RUN_TEMP/wao-images.tar.gz"
  : >"$desired"
  : >"$manifest"
  rm -f -- "$archive"
  run_docker() { docker "$@"; }
  . deploy/scripts/image-content.sh
  for image in \
    "wao-server:$WAO_GIT_SHA" \
    "wao-agent-server:$WAO_GIT_SHA" \
    "wao-gateway:$WAO_GIT_SHA" \
    mysql:9.7.0 redis:7-alpine jeessy/ddns-go:v6.17.2; do
    image_id="$(docker image inspect --format '{{.Id}}' "$image")" || return 1
    [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
    fingerprint='-'
    content_tag='-'
    case "$image" in
      wao-*)
        fingerprint="$(image_content_fingerprint "$image")" || return 1
        [[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || return 1
        content_tag="${image%%:*}:content-${fingerprint:0:12}"
        content_tags="$content_tags $content_tag"
        ;;
    esac
    printf '%s %s %s %s\n' "$image" "$image_id" "$fingerprint" "$content_tag" >>"$desired"
  done
  # 一次 SSH 同时查询精确镜像 ID 和运行内容标签。只有完整指纹一致才复用
  # Linux1 已有镜像；清单改写为远端真实 ID，安装器仍按 ID 验证并贴目标 SHA 标签。
  present_report="$(slave_ssh -o BatchMode=yes -o ConnectTimeout=8 "wao-slave" \
    "for id in $(awk '{ print $2 }' "$desired" | tr '\n' ' '); do docker image inspect \"\$id\" >/dev/null 2>&1 && printf 'id %s\\n' \"\$id\"; done; for tag in $content_tags; do remote_id=\$(docker image inspect --format '{{.Id}}' \"\$tag\" 2>/dev/null) || continue; content=\$(docker image inspect --format '{{.Os}}/{{.Architecture}} {{json .Config}} {{json .RootFS.Layers}}' \"\$tag\") || continue; fp=\$(printf '%s' \"\$content\" | openssl dgst -sha256 -r | cut -d' ' -f1) || continue; printf 'content %s %s %s\\n' \"\$tag\" \"\$remote_id\" \"\$fp\"; done; true")" || return 1
  while read -r image image_id fingerprint content_tag; do
    resolved_id="$image_id"
    if ! printf '%s\n' "$present_report" | grep -Fqx "id $image_id"; then
      resolved_id=''
      if [ "$content_tag" != '-' ]; then
        resolved_id="$(printf '%s\n' "$present_report" | awk -v tag="$content_tag" -v fp="$fingerprint" \
          '$1 == "content" && $2 == tag && $4 == fp { print $3; exit }')"
      fi
    fi
    if [[ ! "$resolved_id" =~ ^sha256:[0-9a-f]{64}$ ]]; then
      missing_images+=("$image")
      resolved_id="$image_id"
    fi
    printf '%s %s\n' "$image" "$resolved_id" >>"$manifest"
  done <"$desired"
  if [ "${#missing_images[@]}" -eq 0 ]; then
    echo 'Linux1 已有全部目标镜像，仅发送版本清单。'
    return 0
  fi
  printf 'Linux1 缺少 %s 个镜像，生成增量归档。\n' "${#missing_images[@]}"
  (set -o pipefail; docker save "${missing_images[@]}" | gzip -1 >"$archive") || return 1
  gzip -t "$archive" || return 1
  chmod 0640 "$archive"
}

deploy_slave() {
  prepare_slave_images || return 1
  release_suffix="${BUILD_NUMBER}-${WAO_BUILD_ATTEMPT:-1}"
  remote_dir="deploy/incoming/${release_suffix}"
  retry_remote "Linux1 从节点" slave_ssh -o ConnectTimeout=15 "wao-slave" \
    "install -d -m 0700 '$remote_dir'" || return
  # remote_dir is unique per build attempt, so the original basenames cannot
  # collide. Transfer the installer with the inputs in one SSH session.
  retry_remote "Linux1 从节点" slave_scp -o ConnectTimeout=15 \
    wao-release.tgz wao-secrets.env wao-shared.env wao-gateway.p12 \
    "$WAO_RUN_TEMP/wao-image-manifest.txt" deploy/scripts/install-release-linux.sh \
    "wao-slave:$remote_dir/" || return
  remote_image_arg='-'
  if [ -f "$WAO_RUN_TEMP/wao-images.tar.gz" ]; then
    retry_remote "Linux1 增量镜像" slave_scp -o ConnectTimeout=15 \
      "$WAO_RUN_TEMP/wao-images.tar.gz" "wao-slave:$remote_dir/" || return
    remote_image_arg="$remote_dir/wao-images.tar.gz"
  fi
  snapshot_argument=""
  if [ "$BOOTSTRAP_SLAVE" = true ]; then
    test -s wao-bootstrap.sql.gz || return 1
    retry_remote "Linux1 从节点" slave_scp -o ConnectTimeout=15 wao-bootstrap.sql.gz \
      "wao-slave:$remote_dir/" || return
    snapshot_argument="$remote_dir/wao-bootstrap.sql.gz"
  fi
  run_slave_installer \
    "sh '$remote_dir/install-release-linux.sh' '$remote_dir/wao-release.tgz' '$remote_dir/wao-secrets.env' '$remote_dir/wao-shared.env' '$remote_dir/wao-gateway.p12' '$remote_image_arg' '$WAO_GIT_SHA' '$snapshot_argument' '' '$remote_dir/wao-image-manifest.txt'" || return
}

prepare_cloud_routing() {
  retry_remote "云前端路由" scp -o ConnectTimeout=15 \
    deploy/nginx/wao.wayuwa.cn.conf.template \
    deploy/nginx/jms.wayuwa.cn.conf \
    deploy/scripts/activate-nginx-config.sh deploy/scripts/render-nginx-config.sh \
    deploy/scripts/resolve-gateway-ipv4.sh \
    deploy/scripts/ensure-jumpserver-certificate.sh \
    deploy/scripts/set-nginx-upstream-state.sh \
    deploy/infrastructure/health-watch.py \
    "wao-cloud:/tmp/" || return
  retry_remote "云前端路由" ssh -o ConnectTimeout=15 "wao-cloud" '
    set -eu
    install -d -m 0755 /opt/wao/deploy/nginx /opt/wao/deploy/scripts
    install -m 0644 /tmp/wao.wayuwa.cn.conf.template /opt/wao/deploy/nginx/wao.wayuwa.cn.conf.template
    install -m 0644 /tmp/jms.wayuwa.cn.conf /opt/wao/deploy/nginx/jms.wayuwa.cn.conf
    install -m 0755 /tmp/activate-nginx-config.sh /opt/wao/deploy/scripts/activate-nginx-config.sh
    install -m 0755 /tmp/render-nginx-config.sh /opt/wao/deploy/scripts/render-nginx-config.sh
    install -m 0755 /tmp/resolve-gateway-ipv4.sh /opt/wao/deploy/scripts/resolve-gateway-ipv4.sh
    install -m 0755 /tmp/set-nginx-upstream-state.sh /opt/wao/deploy/scripts/set-nginx-upstream-state.sh
    install -d -m 0755 /opt/wao-health-watch
    install -m 0644 /tmp/health-watch.py /opt/wao-health-watch/health-watch.py
    install -m 0755 /tmp/ensure-jumpserver-certificate.sh /opt/wao/deploy/scripts/ensure-jumpserver-certificate.sh
    # 新主配置已经接管 /game 与 /insight；首次摘流也会渲染它，必须先移除旧片段，
    # 否则 nginx -t 会因 duplicate location 拒绝摘流，永远到不了 deploy_cloud 的清理步骤。
    install -d -m 0755 /etc/nginx/wao-external-apps.d
    rm -f /etc/nginx/wao-external-apps.d/wao-game.conf \
      /etc/nginx/wao-external-apps.d/wao-insight.conf
  ' || return
  mark_deploy_window
}

# 云端独立监控在标记后一段时间内不告警（ADR-280）；开始与结束各刷新一次，
# 覆盖并行替换期间及之后监控的两次连续失败判定。
mark_deploy_window() {
  ssh -o ConnectTimeout=15 "wao-cloud" \
    'install -d -m 0755 /run/wao-nginx-upstreams && touch /run/wao-nginx-upstreams/deploying'
}

deploy_cloud() {
  stage_cloud && activate_cloud
}

# 只上传与安装不可变制品，不改变线上入口；可以和节点部署并行。
stage_slave_static() {
  # A disabled/offline slave never receives an incomplete release advertisement.
  if [ "$slave_available" = true ]; then
    retry_remote "从节点静态目录" ssh -o ConnectTimeout=15 wao-slave \
      'install -d "$HOME/deploy/wao-public-edge/releases" "$HOME/deploy/wao-public-edge/incoming"' || return
    retry_remote "从节点静态制品" scp -o ConnectTimeout=15 \
      "$WAO_RUN_TEMP/wao-static.tgz" deploy/scripts/static-release.py \
      wao-slave:deploy/wao-public-edge/incoming/ || return
    retry_remote "从节点静态校验" ssh -o ConnectTimeout=15 wao-slave \
      "python3 deploy/wao-public-edge/incoming/static-release.py install deploy/wao-public-edge/incoming/wao-static.tgz deploy/wao-public-edge/releases '$WAO_GIT_SHA'" || return
  fi
}

stage_cloud_static() {
  retry_remote "云静态制品" scp -o ConnectTimeout=15 \
    "$WAO_RUN_TEMP/wao-static.tgz" deploy/scripts/static-release.py \
    deploy/scripts/publish-static-routing.py wao-cloud:/tmp/ || return
  retry_remote "云静态校验" ssh -o ConnectTimeout=15 wao-cloud \
    "python3 /tmp/static-release.py install /tmp/wao-static.tgz /var/www/wao/releases '$WAO_GIT_SHA'" || return
  retry_remote "云前端" ssh -o ConnectTimeout=15 "wao-cloud" '
    set -eu
    install -d /tmp/wao-agent-dist
    find /tmp/wao-agent-dist -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  ' || return
  retry_remote "云前端" scp -o ConnectTimeout=15 -r agent-web/dist/. "wao-cloud:/tmp/wao-agent-dist/" || return
  retry_remote "云前端" scp -o ConnectTimeout=15 \
    deploy/nginx/wao.wayuwa.cn.conf.template \
    deploy/nginx/jms.wayuwa.cn.conf \
    deploy/scripts/activate-nginx-config.sh deploy/scripts/render-nginx-config.sh \
    deploy/scripts/resolve-gateway-ipv4.sh \
    deploy/scripts/ensure-jumpserver-certificate.sh \
    deploy/scripts/set-nginx-upstream-state.sh \
    "wao-cloud:/tmp/"
}

# 两台边缘节点使用同一份已校验静态归档；打包后并行传输，避免反向隧道
# 与云端上传相互等待。公共入口仍由 activate_cloud 在应用就绪后切换。
stage_cloud() {
  local slave_pid cloud_pid slave_result=0 cloud_result=0
  python3 deploy/scripts/static-release.py pack wao-web/dist "$WAO_RUN_TEMP/wao-static.tgz" "$WAO_GIT_SHA" || return
  python3 deploy/scripts/static-release.py install "$WAO_RUN_TEMP/wao-static.tgz" \
    "${JENKINS_HOME:-/var/lib/jenkins}/wao-static/releases" "$WAO_GIT_SHA" || return
  (started_at="$(date +%s)"; stage_slave_static; result=$?;
    printf 'slave-static elapsed=%ss result=%s\n' "$(($(date +%s) - started_at))" "$result";
    exit "$result") & slave_pid=$!
  (started_at="$(date +%s)"; stage_cloud_static; result=$?;
    printf 'cloud-static elapsed=%ss result=%s\n' "$(($(date +%s) - started_at))" "$result";
    exit "$result") & cloud_pid=$!
  wait "$slave_pid" || slave_result=$?
  wait "$cloud_pid" || cloud_result=$?
  [ "$slave_result" -eq 0 ] && [ "$cloud_result" -eq 0 ]
}

# 切换公网网页与 Nginx 配置；必须在两台应用节点都就绪后执行。
activate_cloud() {
  retry_remote "静态路由清单" ssh -o ConnectTimeout=15 wao-cloud \
    "python3 /tmp/publish-static-routing.py /etc/wao-public-edge/routing.json '$WAO_GIT_SHA'" || return
  # Render on the cloud host: its Linux1 name resolves to the reverse SSH
  # tunnel. Rendering on Jenkins would write the unreachable public :9000 IP.
  retry_remote "云端 Gateway 配置" ssh -o ConnectTimeout=15 wao-cloud \
    "WAO_NGINX_RENDER_OUTPUT=/tmp/wao-nginx.conf sh /opt/wao/deploy/scripts/render-nginx-config.sh" || return
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
    install -m 0755 /tmp/resolve-gateway-ipv4.sh /opt/wao/deploy/scripts/resolve-gateway-ipv4.sh
    install -m 0755 /tmp/set-nginx-upstream-state.sh /opt/wao/deploy/scripts/set-nginx-upstream-state.sh
    install -m 0755 /tmp/ensure-jumpserver-certificate.sh /opt/wao/deploy/scripts/ensure-jumpserver-certificate.sh
    rm -f /tmp/wao.wayuwa.cn.conf.template /tmp/activate-nginx-config.sh \
      /tmp/render-nginx-config.sh /tmp/resolve-gateway-ipv4.sh /tmp/set-nginx-upstream-state.sh \
      /tmp/jms.wayuwa.cn.conf /tmp/ensure-jumpserver-certificate.sh
    printf "%s\n" \
      "SHELL=/bin/sh" \
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      "* * * * * root /opt/wao/deploy/scripts/activate-nginx-config.sh >/dev/null 2>&1" \
      >/etc/cron.d/wao-nginx-ddns-reload
    chmod 0644 /etc/cron.d/wao-nginx-ddns-reload
    install -d /var/www/wao/dist
    # Keep legacy hashed chunks for already-open pages. Publish entry HTML last.
    # Old immutable releases are retained until an explicit retention policy.
    release_dir=/var/www/wao/releases/'"$WAO_GIT_SHA"'
    test -f "$release_dir/index.html"
    find "$release_dir" -mindepth 1 -maxdepth 1 ! -name index.html \
      ! -name release-manifest.json -exec cp -a -- {} /var/www/wao/dist/ \;
    install -m 0644 "$release_dir/index.html" /var/www/wao/dist/.index.html.next
    mv -f /var/www/wao/dist/.index.html.next /var/www/wao/dist/index.html
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
    "gateway_ip=\$(sh /opt/wao/deploy/scripts/resolve-gateway-ipv4.sh '$gateway_host') && response=\$(curl -4 -kfsS --max-time 8 --resolve '$gateway_host:9000:'\$gateway_ip https://$gateway_host:9000/api/edge/ready) && printf '%s' \"\$response\" | grep -Eq '\"code\"[[:space:]]*:[[:space:]]*0' && printf '%s' \"\$response\" | grep -Eq '\"protocol\"[[:space:]]*:[[:space:]]*1'"
}

public_ready() {
  response="$(curl --fail --silent --show-error --max-time 8 \
    https://wao.wayuwa.cn/api/edge/ready)" || return 1
  printf '%s' "$response" | grep -Eq '"code"[[:space:]]*:[[:space:]]*0' &&
    printf '%s' "$response" | grep -Eq '"protocol"[[:space:]]*:[[:space:]]*1'
}

# 刚完成 Nginx reload 或容器替换时给入口几秒收敛；仍以数据库就绪路由为准（ADR-278）。
wait_until_ready() {
  local attempt=1
  until "$@"; do
    [ "$attempt" -lt 10 ] || return 1
    attempt=$((attempt + 1))
    sleep 2
  done
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

# 路由状态只由已验证的就绪结果决定，不能把尚未部署的 pending 当作健康。
restore_upstreams() {
  local master_state=up slave_state=up
  [ "$master_routable" = true ] || master_state=down
  [ "$slave_routable" = true ] || slave_state=down
  retry_remote "云前端路由" ssh -o ConnectTimeout=15 "wao-cloud" "
    for pair in master:$master_state slave:$slave_state; do
      node=\${pair%%:*}
      state=\${pair#*:}
      if [ \"\$state\" = up ] && [ ! -f \"/run/wao-nginx-upstreams/\$node.down\" ]; then continue; fi
      /opt/wao/deploy/scripts/set-nginx-upstream-state.sh \"\$node\" \"\$state\" || exit 1
    done"
}

availability_pid=''
availability_stop="$WAO_RUN_TEMP/wao-availability.stop"
availability_failures="$WAO_RUN_TEMP/wao-availability.failures"
: >"$availability_failures"
rm -f "$availability_stop"

start_availability_monitor() {
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

stop_availability_monitor() {
  if [ -n "$availability_pid" ]; then
    touch "$availability_stop"
    wait "$availability_pid" || true
    availability_pid=''
  fi
}

cleanup_rollout() {
  stop_availability_monitor
  if [ -n "${stage_pid:-}" ]; then wait "$stage_pid" || true; fi
  if ! restore_upstreams; then
    echo '无法确认故障节点已从公网摘流；请检查云端 Nginx 路由。' >&2
  fi
  mark_deploy_window >/dev/null 2>&1 || true
  cleanup_deploy_inputs
}

run_node_job() {
  local label="$1" deploy_function="$2" log_file="$3" result_file="$4" result=failed started_at
  started_at="$(date +%s)"
  if retry_node_deploy "$label" "$deploy_function" "$log_file" >/dev/null 2>&1; then result=success; fi
  printf '%s %s %s\n' "$result" "${NODE_DEPLOY_ATTEMPTS:-0}" \
    "$(($(date +%s) - started_at))" >"$result_file"
}

run_stage_job() {
  local result=failed started_at
  started_at="$(date +%s)"
  if stage_cloud >"$1" 2>&1; then result=success; fi
  printf '%s %s\n' "$result" "$(($(date +%s) - started_at))" >"$2"
}

# 读取后台节点任务结果写入 recorded_result，并同步飞书通知使用的状态文件。
record_node_result() {
  local node="$1" log_file="$2" result_file="$3" result=failed attempts=0 elapsed=unknown
  read -r result attempts elapsed <"$result_file" 2>/dev/null || true
  printf '\n===== %s =====\n' "$node"; cat "$log_file" 2>/dev/null || true
  printf '%s elapsed=%ss attempts=%s result=%s\n' "$node" "$elapsed" "$attempts" "$result"
  set_status "${node}_attempts" "$attempts"
  if [ "$result" = success ]; then
    set_status "${node}_status" success
    set_status "${node}_detail" "$WAO_GIT_SHA"
  else
    set_status "${node}_status" failed
    set_status "${node}_detail" "$(tail -n 1 "$log_file" 2>/dev/null)"
  fi
  recorded_result="$result"
}

# 前端快路径必须由本次完整变更范围确认，不能只信任一个外部环境开关。
deploy_browser_only() {
  [ "$BOOTSTRAP_SLAVE" = false ] && [ "$ALLOW_SLAVE_OFFLINE" = false ] || return 1
  local plan
  plan="$(sh deploy/jenkins/ci-change-plan.sh "$WAO_CHANGE_BASE" "$WAO_GIT_SHA")" || return 1
  printf '%s\n' "$plan" | grep -qx 'WAO_WEB_ONLY=true' || return 1
  prepare_cloud_routing || return 1
  gateway_ready_from_cloud "$MASTER_HOST" || return 1
  gateway_ready_from_cloud "$SLAVE_HOST" || return 1
  start_availability_monitor
  deploy_cloud || { set_status cloud_status failed; return 1; }
  gateway_ready_from_cloud "$MASTER_HOST" || return 1
  gateway_ready_from_cloud "$SLAVE_HOST" || return 1
  stop_availability_monitor
  [ ! -s "$availability_failures" ] || return 1
  set_status master_status success
  set_status master_detail '浏览器发布，保留已验证健康的原后端版本'
  set_status slave_status success
  set_status slave_detail '浏览器发布，保留已验证健康的原后端版本'
  set_status cloud_status success
  set_status cloud_detail "$WAO_GIT_SHA"
  set_status verification_status success
  set_status verification_detail '静态制品已校验发布，双节点健康且公共入口持续可用'
  set_status publish_completed_at "$(TZ=Asia/Shanghai date '+%Y-%m-%d %H:%M:%S')"
}


if [ "${WAO_WEB_ONLY:-false}" = true ]; then
  trap 'stop_availability_monitor; cleanup_deploy_inputs' EXIT HUP INT TERM
  deploy_browser_only || exit 1
  exit 0
fi

# V77 的一次性维护窗口早已完成；并行快速发布不再实现该窗口，状态异常时 fail-closed。
rollout_status_output="$(quiesce_master rollout-status)" || exit 1
rollout_status="$(printf '%s\n' "$rollout_status_output" | tr -d '\r' | tail -n 1)"
if [ "$rollout_status" != complete ]; then
  printf 'V77 发布状态为 %s；并行快速发布不处理该维护窗口，拒绝发布。\n' "$rollout_status"
  exit 1
fi

sync_jumpserver=false
if batch_changed '^deploy/jumpserver/'; then sync_jumpserver=true; fi
sync_infrastructure=false
if batch_changed '^deploy/infrastructure/'; then sync_infrastructure=true; fi

if ! prepare_cloud_routing >routing.log 2>&1; then
  printf '\n===== cloud routing =====\n'; cat routing.log
  exit 1
fi

master_result=pending
slave_result=pending
master_routable=false
slave_routable=false
trap cleanup_rollout EXIT

# 先确认至少一台正在服务；单节点时不能重启唯一健康的应用。
if wait_until_ready gateway_ready_from_cloud "$MASTER_HOST"; then master_routable=true; fi
if wait_until_ready gateway_ready_from_cloud "$SLAVE_HOST"; then slave_routable=true; fi
restore_upstreams || exit 1
if [ "$master_routable" != true ] && [ "$slave_routable" != true ]; then
  echo '两台 Gateway 均未通过数据库就绪检查；停止发布。' >&2
  exit 1
fi
if ! slave_ssh -o BatchMode=yes -o ConnectTimeout=8 -o ConnectionAttempts=1 \
    "wao-slave" 'docker info' >/dev/null; then
  slave_available=false
  slave_result=failed
  set_status slave_status failed
  set_status slave_detail 'Linux1 SSH 或 Docker 预检失败；仅按 Gateway 就绪结果保留路由'
fi
start_availability_monitor

if ! install_master_deploy_helper; then
  set_status master_status failed
  set_status master_detail '无法安装本次发布的 Linux2 部署助手'
  exit 1
fi

rollout_slave() {
  [ "$master_routable" = true ] || return 1
  slave_routable=false
  restore_upstreams || return 1
  wait_until_ready public_ready || return 1
  run_node_job Linux1 deploy_slave slave.log "$WAO_RUN_TEMP/slave.result"
  record_node_result slave slave.log "$WAO_RUN_TEMP/slave.result"
  slave_result="$recorded_result"
  if [ "$slave_result" != success ] || ! wait_until_ready gateway_ready_from_cloud "$SLAVE_HOST"; then
    slave_result=failed
    set_status slave_status failed
    set_status slave_detail 'Linux1 部署或云端数据库就绪检查失败；保留 Linux2 当前版本'
    return 1
  fi
  slave_routable=true
  restore_upstreams || return 1
  if ! wait_until_ready public_ready; then
    slave_routable=false
    restore_upstreams || true
    set_status slave_status failed
    set_status slave_detail 'Linux1 回流后公共入口数据库就绪检查失败，已重新摘流'
    return 1
  fi
}

rollout_master() {
  [ "$slave_routable" = true ] || return 1
  master_routable=false
  restore_upstreams || return 1
  wait_until_ready public_ready || return 1
  run_node_job Linux2 deploy_master master.log "$WAO_RUN_TEMP/master.result"
  record_node_result master master.log "$WAO_RUN_TEMP/master.result"
  master_result="$recorded_result"
  if [ "$master_result" != success ] || ! wait_until_ready gateway_ready_from_cloud "$MASTER_HOST"; then
    master_result=failed
    set_status master_status failed
    set_status master_detail 'Linux2 部署或云端数据库就绪检查失败；Linux1 保持服务'
    return 1
  fi
  master_routable=true
  restore_upstreams || return 1
  if ! wait_until_ready public_ready; then
    master_routable=false
    restore_upstreams || true
    set_status master_status failed
    set_status master_detail 'Linux2 回流后公共入口数据库就绪检查失败，已重新摘流'
    return 1
  fi
}

# 静态制品预传与节点滚动独立；任何时刻只重启一台应用。
echo "逐节点发布 Linux1、Linux2，并预传云端静态制品（JumpServer 同步=$sync_jumpserver，基础设施同步=$sync_infrastructure）。"
if [ "$slave_available" != true ] && [ "$slave_routable" != true ]; then
  echo 'Linux1 不可达且未就绪；拒绝重启唯一健康的 Linux2。' >&2
  exit 1
fi
run_stage_job cloud-stage.log "$WAO_RUN_TEMP/cloud-stage.result" &
stage_pid=$!
if [ "$master_routable" = true ]; then
  if [ "$slave_available" = true ]; then rollout_slave || exit 1; fi
  rollout_master || exit 1
else
  # Linux2 应用已不可用时，先修复它，不能重启唯一健康的 Linux1。
  rollout_master || exit 1
  if [ "$slave_available" = true ]; then rollout_slave || exit 1; fi
fi

wait "$stage_pid" || true
stage_pid=''
printf '\n===== cloud stage =====\n'; cat cloud-stage.log
stage_result=failed stage_elapsed=unknown
read -r stage_result stage_elapsed <"$WAO_RUN_TEMP/cloud-stage.result" 2>/dev/null || true
printf 'cloud-stage elapsed=%ss result=%s\n' "$stage_elapsed" "$stage_result"

if [ "$slave_available" != true ]; then
  echo 'Linux1 机器不可用；Linux2 已保持服务，完整后端发布不能重启唯一健康节点。'
  exit 1
fi
if [ "$master_result" != success ] || [ "$slave_result" != success ]; then
  echo "节点部署未完成：Linux2=$master_result，Linux1=$slave_result；网页入口保持旧版本。"
  exit 1
fi
if [ "$stage_result" != success ]; then
  set_status cloud_status failed
  set_status cloud_detail '云端静态制品预传失败'
  exit 1
fi

if ! wait_until_ready gateway_ready_from_cloud "$MASTER_HOST"; then
  master_result=failed
  master_routable=false
  set_status master_status failed
  set_status master_detail '云节点无法经 Linux2 Gateway 完成数据库就绪检查'
  exit 1
fi
if [ "$slave_available" = true ] && ! wait_until_ready gateway_ready_from_cloud "$SLAVE_HOST"; then
  if [ "$ALLOW_SLAVE_OFFLINE" = true ]; then
    slave_available=false
    slave_routable=false
    set_status slave_status skipped
    set_status slave_detail 'Linux1 已部署，但云节点无法直连 Gateway；保持摘流并继续主节点单机发布'
  else
    slave_result=failed
    slave_routable=false
    set_status slave_status failed
    set_status slave_detail '云节点无法直连 Linux1 Gateway'
    exit 1
  fi
fi

if [ "$sync_jumpserver" = true ]; then
  if ! provision_jumpserver_asset_access; then
    set_status master_status failed
    set_status master_detail 'JumpServer 专用资产公钥安装失败'
    exit 1
  fi
  jumpserver_verify_log="$WAO_RUN_TEMP/jumpserver-verify.log"
  if ! verify_jumpserver_assets >"$jumpserver_verify_log" 2>&1; then
    cat "$jumpserver_verify_log"
    jumpserver_verify_detail="$(tail -n 1 "$jumpserver_verify_log" \
      | tr '\r\n=' '   ' | cut -c1-300)"
    set_status master_status failed
    set_status master_detail \
      "JumpServer 三节点 SSH、资产登记或 SSO 验收失败：${jumpserver_verify_detail:-无错误摘要}"
    exit 1
  fi
  cat "$jumpserver_verify_log"
fi

if ! activate_cloud >cloud.log 2>&1; then
  set_status cloud_status failed
  set_status cloud_detail '云端静态资源或 Nginx 发布失败'
  printf '\n===== cloud =====\n'; cat cloud.log
  exit 1
fi
set_status cloud_status success
set_status cloud_detail "$WAO_GIT_SHA"
printf '\n===== cloud =====\n'; cat cloud.log

# 云端安装会重新渲染 Nginx 文件；立即恢复已验证节点，不能只依赖 EXIT trap。
restore_upstreams || exit 1

if ! wait_until_ready public_ready; then
  set_status verification_status failed
  set_status verification_detail '公共入口数据库就绪检查失败'
  exit 1
fi
stop_availability_monitor
if [ -s "$availability_failures" ]; then
  cat "$availability_failures"
  set_status verification_status failed
  set_status verification_detail '逐台发布期间公共入口出现数据库就绪失败采样'
  exit 1
fi
set_status verification_status success
if [ "$slave_available" = true ]; then
  set_status verification_detail 'Linux1、Linux2、云端与公共入口检查通过'
else
  set_status verification_detail 'Linux1 已显式摘流；Linux2、云端与公共入口检查通过'
fi
set_status publish_completed_at "$(TZ=Asia/Shanghai date '+%Y-%m-%d %H:%M:%S')"
trap - EXIT
mark_deploy_window || true
cleanup_deploy_inputs
