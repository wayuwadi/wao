#!/bin/sh
set -eu

: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"

workspace="$(pwd -P)"
image="wao-server:$WAO_GIT_SHA"
changed=false
expected_override="$(mktemp)"
dynamic_override="$(mktemp)"
cleanup() { rm -f "$expected_override" "$dynamic_override"; }
trap cleanup EXIT HUP INT TERM

needs_update() {
  source_file="$1"
  target_file="$2"
  if ! cmp -s "$source_file" "$target_file"; then
    changed=true
  fi
}

needs_update deploy/jenkins/jenkins.yaml /etc/jenkins/jenkins.yaml
needs_update deploy/jenkins/wao-jenkins-webhook-tunnel.service \
  /etc/systemd/system/wao-jenkins-webhook-tunnel.service

# 安装时追加的仓库路径、SCM 分支和本机代理属于机器配置，不能被仓库模板覆盖。
awk '/^Environment="(WAO_MASTER_PROJECT_ROOT|WAO_JENKINS_SCM_BRANCH|HTTP_PROXY|HTTPS_PROXY|NO_PROXY)=/' \
  /etc/systemd/system/jenkins.service.d/override.conf >"$dynamic_override"
cp deploy/jenkins/jenkins.service.override.conf "$expected_override"
cat "$dynamic_override" >>"$expected_override"
needs_update "$expected_override" /etc/systemd/system/jenkins.service.d/override.conf

if [ "$changed" = false ]; then
  echo 'Jenkins controller configuration is already current.'
  exit 0
fi

# Jenkins 已属于 docker 组并负责生产发布。这里用本次流水线刚构建的可信 main 镜像，
# 只更新三个受版本控制的控制器文件，并让宿主 systemd 重新读取 unit；不挂载 Secret。
docker image inspect "$image" >/dev/null
docker run --rm --privileged --pid=host --cgroupns=host --user 0 \
  -v /:/host \
  -v "$workspace:/workspace:ro" \
  --entrypoint /bin/sh "$image" -ceu '
    # 先进入宿主 mount/pid namespace 验证 systemd 通道，避免复制配置后才发现无法 reload。
    /host/usr/bin/nsenter --target 1 --mount --pid -- \
      /usr/bin/systemctl show --property=FragmentPath jenkins.service >/dev/null
    cp /workspace/deploy/jenkins/jenkins.yaml /host/etc/jenkins/jenkins.yaml
    awk '\''/^Environment="(WAO_MASTER_PROJECT_ROOT|WAO_JENKINS_SCM_BRANCH|HTTP_PROXY|HTTPS_PROXY|NO_PROXY)=/'\'' \
      /host/etc/systemd/system/jenkins.service.d/override.conf >/tmp/wao-jenkins-dynamic.conf
    cp /workspace/deploy/jenkins/jenkins.service.override.conf \
      /host/etc/systemd/system/jenkins.service.d/override.conf
    cat /tmp/wao-jenkins-dynamic.conf \
      >>/host/etc/systemd/system/jenkins.service.d/override.conf
    cp /workspace/deploy/jenkins/wao-jenkins-webhook-tunnel.service \
      /host/etc/systemd/system/wao-jenkins-webhook-tunnel.service
    chown 0:0 /host/etc/jenkins/jenkins.yaml \
      /host/etc/systemd/system/jenkins.service.d/override.conf \
      /host/etc/systemd/system/wao-jenkins-webhook-tunnel.service
    chmod 0644 /host/etc/jenkins/jenkins.yaml \
      /host/etc/systemd/system/jenkins.service.d/override.conf \
      /host/etc/systemd/system/wao-jenkins-webhook-tunnel.service
    /host/usr/bin/nsenter --target 1 --mount --pid -- \
      /usr/bin/systemctl daemon-reload
  '

admin_password="$(tr -d '\r\n' </etc/jenkins/secrets/admin-password)"
jenkins_base=''
if curl --fail --silent --show-error -u "admin:$admin_password" \
  http://127.0.0.1:8081/jenkins/api/json >/dev/null 2>&1; then
  jenkins_base='/jenkins'
elif ! curl --fail --silent --show-error -u "admin:$admin_password" \
  http://127.0.0.1:8081/api/json >/dev/null; then
  echo 'Unable to reach the running Jenkins controller before safe restart.' >&2
  exit 1
fi

crumb_json="$(curl --fail --silent --show-error -u "admin:$admin_password" \
  "http://127.0.0.1:8081${jenkins_base}/crumbIssuer/api/json")"
crumb_field="$(printf '%s' "$crumb_json" | jq -r '.crumbRequestField')"
crumb="$(printf '%s' "$crumb_json" | jq -r '.crumb')"
test -n "$crumb_field"
test -n "$crumb"

# safeRestart 会等当前流水线和 post 动作结束后才退出；systemd 随后用新前缀拉起控制器。
curl --fail --silent --show-error -u "admin:$admin_password" \
  -H "$crumb_field: $crumb" -X POST \
  "http://127.0.0.1:8081${jenkins_base}/safeRestart" >/dev/null
echo 'Jenkins controller safe restart requested; it will run after this build becomes idle.'
