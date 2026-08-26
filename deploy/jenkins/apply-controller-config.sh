#!/bin/sh
set -eu

: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"

workspace="$(pwd -P)"
image="wao-server:$WAO_GIT_SHA"
changed=false
plugins_changed=false
expected_override="$(mktemp)"
dynamic_override="$(mktemp)"
controller_cookie="$(mktemp)"
cleanup() { rm -f "$expected_override" "$dynamic_override" "$controller_cookie"; }
trap cleanup EXIT HUP INT TERM

needs_update() {
  source_file="$1"
  target_file="$2"
  if ! cmp -s "$source_file" "$target_file"; then
    changed=true
  fi
}

needs_update deploy/jenkins/jenkins.yaml /etc/jenkins/jenkins.yaml
if ! cmp -s deploy/jenkins/plugins.txt /etc/jenkins/plugins.txt; then
  changed=true
  plugins_changed=true
fi
needs_update deploy/jenkins/backup-controller.sh /usr/local/lib/wao-jenkins/backup-controller.sh
needs_update deploy/jenkins/wao-jenkins-webhook-tunnel.service \
  /etc/systemd/system/wao-jenkins-webhook-tunnel.service

# 安装时追加的仓库路径、SCM 分支和本机代理属于机器配置，不能被仓库模板覆盖。
awk '/^Environment="(WAO_MASTER_PROJECT_ROOT|WAO_JENKINS_SCM_BRANCH|HTTP_PROXY|HTTPS_PROXY|NO_PROXY)=/' \
  /etc/systemd/system/jenkins.service.d/override.conf >"$dynamic_override"
cp deploy/jenkins/jenkins.service.override.conf "$expected_override"
cat "$dynamic_override" >>"$expected_override"
needs_update "$expected_override" /etc/systemd/system/jenkins.service.d/override.conf

if [ "$changed" = false ]; then
  for candidate_base in /jenkins ''; do
    if curl --fail --silent --show-error \
      -H 'X-Forwarded-User: wao-controller-runtime-check' \
      -H 'X-Forwarded-Groups: wao-admins' \
      "http://127.0.0.1:8081${candidate_base}/api/json" >/dev/null 2>&1; then
      echo 'Jenkins controller configuration is already current and active.'
      exit 0
    fi
  done
  echo 'Jenkins controller files are current, but the running security realm still needs activation.'
  changed=true
fi

# JCasC 只能在所需插件已经落盘后加载。插件管理器以 Jenkins 用户运行，复用控制器服务的代理环境，
# 只按固定版本清单补齐插件和依赖；随后 safeRestart 一次性加载插件与新配置。
if [ "$plugins_changed" = true ]; then
  java -jar /usr/local/lib/wao-jenkins/jenkins-plugin-manager-2.15.0.jar \
    --war /usr/share/java/jenkins.war \
    --plugin-download-directory /var/lib/jenkins/plugins \
    --plugin-file deploy/jenkins/plugins.txt \
    --latest=false
fi

# Jenkins 已属于 docker 组并负责生产发布。这里用本次流水线刚构建的可信 main 镜像，
# 只更新受版本控制的控制器配置、插件清单、备份脚本和 systemd 文件，并让宿主重新读取 unit；不挂载 Secret。
docker image inspect "$image" >/dev/null
docker run --rm --privileged --pid=host --cgroupns=host --user 0 \
  -v /:/host \
  -v "$workspace:/workspace:ro" \
  --entrypoint /bin/sh "$image" -ceu '
    # 先进入宿主 mount/pid namespace 验证 systemd 通道，避免复制配置后才发现无法 reload。
    chroot /host /usr/bin/nsenter --target 1 --mount --pid -- \
      /usr/bin/systemctl show --property=FragmentPath jenkins.service >/dev/null
    cp /workspace/deploy/jenkins/jenkins.yaml /host/etc/jenkins/jenkins.yaml
    cp /workspace/deploy/jenkins/plugins.txt /host/etc/jenkins/plugins.txt
    cp /workspace/deploy/jenkins/backup-controller.sh \
      /host/usr/local/lib/wao-jenkins/backup-controller.sh
    awk '\''/^Environment="(WAO_MASTER_PROJECT_ROOT|WAO_JENKINS_SCM_BRANCH|HTTP_PROXY|HTTPS_PROXY|NO_PROXY)=/'\'' \
      /host/etc/systemd/system/jenkins.service.d/override.conf >/tmp/wao-jenkins-dynamic.conf
    cp /workspace/deploy/jenkins/jenkins.service.override.conf \
      /host/etc/systemd/system/jenkins.service.d/override.conf
    cat /tmp/wao-jenkins-dynamic.conf \
      >>/host/etc/systemd/system/jenkins.service.d/override.conf
    cp /workspace/deploy/jenkins/wao-jenkins-webhook-tunnel.service \
      /host/etc/systemd/system/wao-jenkins-webhook-tunnel.service
    chown 0:0 /host/etc/jenkins/jenkins.yaml /host/etc/jenkins/plugins.txt \
      /host/etc/systemd/system/jenkins.service.d/override.conf \
      /host/etc/systemd/system/wao-jenkins-webhook-tunnel.service
    chown 0:0 /host/usr/local/lib/wao-jenkins/backup-controller.sh
    chmod 0644 /host/etc/jenkins/jenkins.yaml /host/etc/jenkins/plugins.txt \
      /host/etc/systemd/system/jenkins.service.d/override.conf \
      /host/etc/systemd/system/wao-jenkins-webhook-tunnel.service
    chmod 0755 /host/usr/local/lib/wao-jenkins/backup-controller.sh
    chroot /host /usr/bin/nsenter --target 1 --mount --pid -- \
      /usr/bin/systemctl daemon-reload
  '

jenkins_base=''
jenkins_auth=''
admin_password=''
if [ -s /etc/jenkins/secrets/admin-password ]; then
  admin_password="$(tr -d '\r\n' </etc/jenkins/secrets/admin-password)"
fi

for candidate_base in /jenkins ''; do
  if curl --fail --silent --show-error \
    -H 'X-Forwarded-User: wao-controller-maintenance' \
    -H 'X-Forwarded-Groups: wao-admins' \
    "http://127.0.0.1:8081${candidate_base}/api/json" >/dev/null 2>&1; then
    jenkins_base="$candidate_base"
    jenkins_auth='proxy'
    break
  fi
  # 首次迁移时运行中的控制器仍使用旧本地认证域；重启后不再依赖这个密码。
  if [ -n "$admin_password" ] && curl --fail --silent --show-error \
    -u "admin:$admin_password" \
    "http://127.0.0.1:8081${candidate_base}/api/json" >/dev/null 2>&1; then
    jenkins_base="$candidate_base"
    jenkins_auth='legacy'
    break
  fi
done

if [ -z "$jenkins_auth" ]; then
  echo 'Unable to reach the running Jenkins controller before safe restart.' >&2
  exit 1
fi

controller_curl() {
  path="$1"
  shift
  if [ "$jenkins_auth" = proxy ]; then
    curl --fail --silent --show-error \
      --cookie "$controller_cookie" --cookie-jar "$controller_cookie" \
      -H 'X-Forwarded-User: wao-controller-maintenance' \
      -H 'X-Forwarded-Groups: wao-admins' "$@" \
      "http://127.0.0.1:8081${jenkins_base}${path}"
  else
    curl --fail --silent --show-error \
      --cookie "$controller_cookie" --cookie-jar "$controller_cookie" \
      -u "admin:$admin_password" "$@" \
      "http://127.0.0.1:8081${jenkins_base}${path}"
  fi
}

crumb_json="$(controller_curl /crumbIssuer/api/json)"
crumb_field="$(printf '%s' "$crumb_json" | jq -r '.crumbRequestField')"
crumb="$(printf '%s' "$crumb_json" | jq -r '.crumb')"
test -n "$crumb_field"
test -n "$crumb"

# safeRestart 会等当前流水线和 post 动作结束后才退出；systemd 随后用新前缀拉起控制器。
controller_curl /safeRestart -H "$crumb_field: $crumb" -X POST >/dev/null
echo 'Jenkins controller safe restart requested; it will run after this build becomes idle.'
