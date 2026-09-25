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
needs_update /etc/jenkins/secrets/github-known-hosts /var/lib/jenkins/.ssh/known_hosts
if ! cmp -s deploy/jenkins/plugins.txt /etc/jenkins/plugins.txt; then
  changed=true
  plugins_changed=true
fi
needs_update deploy/jenkins/controller-health.py /usr/local/lib/wao-jenkins/controller-health.py
for unit in wao-jenkins-health.service wao-jenkins-health.timer; do
  needs_update "deploy/jenkins/$unit" "/etc/systemd/system/$unit"
done
needs_update deploy/jenkins/backup-controller.sh /usr/local/lib/wao-jenkins/backup-controller.sh
needs_update deploy/jenkins/deploy-master-local.sh /usr/local/lib/wao-jenkins/deploy-master-local.sh
for backup_script in \
  configure-offsite-backup.sh create-offsite-backup.sh restore-offsite-backup.sh \
  upload-peer-offsite.sh restore-peer-offsite.sh run-readable-content.sh \
  export-readable-content.py webdav_client.py \
  create-peer-backup.sh verify-peer-restore.sh peer-retention.py check-peer-backup.py; do
  needs_update "deploy/backup/$backup_script" "/usr/local/lib/wao-backup/$backup_script"
done
for unit in wao-peer-backup.service wao-peer-backup.timer; do
  needs_update "deploy/backup/$unit" "/etc/systemd/system/$unit"
done
needs_update deploy/backup/wao-offsite-backup.service \
  /etc/systemd/system/wao-offsite-backup.service
needs_update deploy/backup/wao-offsite-backup.timer \
  /etc/systemd/system/wao-offsite-backup.timer
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
    for executable in /usr/bin/flock /usr/bin/gpg /usr/bin/python3 /usr/bin/split; do
      test -x "/host$executable" || {
        echo "Required offsite backup executable is missing on Linux2: $executable" >&2
        exit 1
      }
    done
    # The release image intentionally has only the unprivileged "wao" user;
    # resolve Jenkins ownership from the mounted host instead of asking the
    # container-side install(1) to resolve a user that does not exist there.
    jenkins_uid="$(chroot /host /usr/bin/id -u jenkins)"
    jenkins_gid="$(chroot /host /usr/bin/id -g jenkins)"
    case "$jenkins_uid:$jenkins_gid" in
      *[!0-9:]*|:|*:|:*)
        echo "Invalid Jenkins host ownership: $jenkins_uid:$jenkins_gid" >&2
        exit 1
        ;;
    esac
    cp /workspace/deploy/jenkins/jenkins.yaml /host/etc/jenkins/jenkins.yaml
    install -d -o "$jenkins_uid" -g "$jenkins_gid" -m 0700 /host/var/lib/jenkins/.ssh
    install -o "$jenkins_uid" -g "$jenkins_gid" -m 0600 \
      /host/etc/jenkins/secrets/github-known-hosts \
      /host/var/lib/jenkins/.ssh/known_hosts
    cp /workspace/deploy/jenkins/plugins.txt /host/etc/jenkins/plugins.txt
    install -o 0 -g 0 -m 0755 /workspace/deploy/jenkins/controller-health.py /host/usr/local/lib/wao-jenkins/controller-health.py
    for unit in wao-jenkins-health.service wao-jenkins-health.timer; do
      install -o 0 -g 0 -m 0644 "/workspace/deploy/jenkins/$unit" "/host/etc/systemd/system/$unit"
    done
    cp /workspace/deploy/jenkins/backup-controller.sh \
      /host/usr/local/lib/wao-jenkins/backup-controller.sh
    cp /workspace/deploy/jenkins/deploy-master-local.sh \
      /host/usr/local/lib/wao-jenkins/deploy-master-local.sh
    install -d -m 0700 /host/etc/wao-backup
    install -d -m 0755 /host/usr/local/lib/wao-backup
    for backup_script in \
      configure-offsite-backup.sh create-offsite-backup.sh restore-offsite-backup.sh \
      upload-peer-offsite.sh restore-peer-offsite.sh run-readable-content.sh \
      create-peer-backup.sh verify-peer-restore.sh peer-retention.py check-peer-backup.py; do
      cp "/workspace/deploy/backup/$backup_script" \
        "/host/usr/local/lib/wao-backup/$backup_script"
      chmod 0755 "/host/usr/local/lib/wao-backup/$backup_script"
    done
    for unit in wao-peer-backup.service wao-peer-backup.timer; do
      install -o 0 -g 0 -m 0644 "/workspace/deploy/backup/$unit" "/host/etc/systemd/system/$unit"
    done
    cp /workspace/deploy/backup/webdav_client.py \
      /host/usr/local/lib/wao-backup/webdav_client.py
    cp /workspace/deploy/backup/export-readable-content.py \
      /host/usr/local/lib/wao-backup/export-readable-content.py
    cp /workspace/deploy/backup/verify-backup-source.py \
      /host/usr/local/lib/wao-backup/verify-backup-source.py
    cp /workspace/deploy/storage/migrate_assets.py \
      /host/usr/local/lib/wao-backup/migrate_assets.py
    cp /workspace/deploy/storage/archive_original_png.py \
      /host/usr/local/lib/wao-backup/archive_original_png.py
    cp /workspace/deploy/storage/optimize_png_webp.py \
      /host/usr/local/lib/wao-backup/optimize_png_webp.py
    cp /workspace/deploy/storage/process-pending-images.sh \
      /host/usr/local/lib/wao-backup/process-pending-images.sh
    cp /workspace/deploy/storage/reclaim_mysql_blobs.py \
      /host/usr/local/lib/wao-backup/reclaim_mysql_blobs.py
    cp /workspace/deploy/storage/export_object_backup.py \
      /host/usr/local/lib/wao-backup/export_object_backup.py
    cp /workspace/deploy/storage/restore_object_backup.py \
      /host/usr/local/lib/wao-backup/restore_object_backup.py
    cp /workspace/deploy/storage/requirements.txt \
      /host/usr/local/lib/wao-backup/storage-requirements.txt
    cp /workspace/deploy/backup/wao-offsite-backup.service \
      /host/etc/systemd/system/wao-offsite-backup.service
    cp /workspace/deploy/backup/wao-offsite-backup.timer \
      /host/etc/systemd/system/wao-offsite-backup.timer
    for unit in wao-image-maintenance.service wao-image-maintenance.timer; do
      cp "/workspace/deploy/storage/$unit" "/host/etc/systemd/system/$unit"
    done
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
    chown 0:0 /host/usr/local/lib/wao-jenkins/deploy-master-local.sh
    chown -R 0:0 /host/usr/local/lib/wao-backup \
      /host/etc/systemd/system/wao-offsite-backup.service \
      /host/etc/systemd/system/wao-offsite-backup.timer \
      /host/etc/systemd/system/wao-image-maintenance.service \
      /host/etc/systemd/system/wao-image-maintenance.timer
    chmod 0644 /host/etc/jenkins/jenkins.yaml /host/etc/jenkins/plugins.txt \
      /host/etc/systemd/system/jenkins.service.d/override.conf \
      /host/etc/systemd/system/wao-jenkins-webhook-tunnel.service
    chmod 0755 /host/usr/local/lib/wao-jenkins/backup-controller.sh
    chmod 0755 /host/usr/local/lib/wao-jenkins/deploy-master-local.sh
    chmod 0755 /host/usr/local/lib/wao-backup/configure-offsite-backup.sh \
      /host/usr/local/lib/wao-backup/create-offsite-backup.sh \
      /host/usr/local/lib/wao-backup/restore-offsite-backup.sh \
      /host/usr/local/lib/wao-backup/upload-peer-offsite.sh \
      /host/usr/local/lib/wao-backup/restore-peer-offsite.sh \
      /host/usr/local/lib/wao-backup/run-readable-content.sh \
      /host/usr/local/lib/wao-backup/export-readable-content.py \
      /host/usr/local/lib/wao-backup/webdav_client.py \
      /host/usr/local/lib/wao-backup/process-pending-images.sh
    chmod 0644 /host/etc/systemd/system/wao-offsite-backup.service \
      /host/etc/systemd/system/wao-offsite-backup.timer \
      /host/etc/systemd/system/wao-image-maintenance.service \
      /host/etc/systemd/system/wao-image-maintenance.timer
    chroot /host /usr/bin/nsenter --target 1 --mount --pid -- \
      /usr/bin/systemctl daemon-reload
    chroot /host /usr/bin/nsenter --target 1 --mount --pid -- \
      /usr/bin/systemctl enable --now wao-offsite-backup.timer
    chroot /host /usr/bin/nsenter --target 1 --mount --pid -- \
      /usr/bin/systemctl enable --now wao-image-maintenance.timer
    chroot /host /usr/bin/nsenter --target 1 --mount --pid -- \
      /usr/bin/systemctl enable --now wao-jenkins-health.timer
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
