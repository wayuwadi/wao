#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo 'Run install-controller.sh as root.' >&2
  exit 1
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
secret_dir='/etc/jenkins/secrets'
jenkins_home='/var/lib/jenkins'
plugin_manager_version='2.15.0'
plugin_manager="/usr/local/lib/wao-jenkins/jenkins-plugin-manager-${plugin_manager_version}.jar"
deploy_owner='wadi'
jenkins_scm_branch="${WAO_JENKINS_SCM_BRANCH:-main}"
jenkins_proxy_port="${WAO_JENKINS_PROXY_PORT:-}"

case "$jenkins_scm_branch" in
  ''|*[!A-Za-z0-9._/-]*)
    echo "Invalid Jenkins SCM branch: $jenkins_scm_branch" >&2
    exit 2
    ;;
esac

if [ -n "$jenkins_proxy_port" ]; then
  case "$jenkins_proxy_port" in
    *[!0-9]*)
      echo "Invalid local Jenkins proxy port: $jenkins_proxy_port" >&2
      exit 2
      ;;
  esac
  if [ "$jenkins_proxy_port" -lt 1 ] || [ "$jenkins_proxy_port" -gt 65535 ]; then
    echo "Invalid local Jenkins proxy port: $jenkins_proxy_port" >&2
    exit 2
  fi
  HTTP_PROXY="http://127.0.0.1:${jenkins_proxy_port}"
  HTTPS_PROXY="$HTTP_PROXY"
  NO_PROXY='127.0.0.1,localhost,linux2.wayuwa.cn,linux1.wayuwa.cn,8.136.152.131'
  http_proxy="$HTTP_PROXY"
  https_proxy="$HTTPS_PROXY"
  no_proxy="$NO_PROXY"
  export HTTP_PROXY HTTPS_PROXY NO_PROXY http_proxy https_proxy no_proxy
fi

command -v getent >/dev/null
getent passwd "$deploy_owner" >/dev/null || {
  echo "Deployment owner does not exist: $deploy_owner" >&2
  exit 1
}
deploy_home="$(getent passwd "$deploy_owner" | cut -d: -f6)"
desktop_dir="$(su -s /bin/sh -c 'xdg-user-dir DESKTOP 2>/dev/null || true' "$deploy_owner")"
: "${desktop_dir:=$deploy_home/Desktop}"
project_root="${WAO_MASTER_PROJECT_ROOT:-$desktop_dir/deploy/wao}"

install -d -m 0750 /etc/jenkins "$secret_dir"
install -d -m 0755 /usr/local/lib/wao-jenkins

generate_secret() {
  target="$1"
  if [ ! -s "$target" ]; then
    umask 077
    openssl rand -base64 36 >"$target"
  fi
}

generate_key() {
  target="$1"
  if [ ! -s "$target" ]; then
    ssh-keygen -q -t ed25519 -N '' -C 'wao-jenkins-deploy' -f "$target"
  fi
}

generate_secret "$secret_dir/admin-password"
generate_secret "$secret_dir/github-webhook-secret"
generate_key "$secret_dir/github-scm-ed25519"
generate_key "$secret_dir/slave-ed25519"
generate_key "$secret_dir/cloud-ed25519"

for required in github-pat github-known-hosts slave-known-hosts cloud-known-hosts; do
  if [ ! -s "$secret_dir/$required" ]; then
    printf 'Missing %s. Follow deploy/jenkins/README.md before installation.\n' \
      "$secret_dir/$required" >&2
    exit 1
  fi
done

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl fontconfig git gnupg jq openssh-client openssl \
  openjdk-21-jre python3 acl sudo unzip zip

install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://pkg.jenkins.io/debian-stable/jenkins.io-2026.key \
  -o /etc/apt/keyrings/jenkins-keyring.asc
printf '%s\n' 'deb [signed-by=/etc/apt/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/' \
  >/etc/apt/sources.list.d/jenkins.list
apt-get update

# The packaged service defaults to 0.0.0.0:8080, where WAO already listens.
# Mask it until the localhost-only override and plugins are in place.
systemctl mask jenkins.service >/dev/null 2>&1 || true
apt-get install -y jenkins
systemctl stop jenkins.service >/dev/null 2>&1 || true
systemctl unmask jenkins.service >/dev/null

install -d -m 0755 /etc/systemd/system/jenkins.service.d
install -m 0644 "$script_dir/jenkins.yaml" /etc/jenkins/jenkins.yaml
install -m 0644 "$script_dir/plugins.txt" /etc/jenkins/plugins.txt
install -m 0755 "$script_dir/controller-health.py" /usr/local/lib/wao-jenkins/controller-health.py
for unit in wao-jenkins-health.service wao-jenkins-health.timer; do
  install -m 0644 "$script_dir/$unit" "/etc/systemd/system/$unit"
done
install -m 0755 "$script_dir/backup-controller.sh" /usr/local/lib/wao-jenkins/backup-controller.sh
install -m 0755 "$script_dir/deploy-master-local.sh" /usr/local/lib/wao-jenkins/deploy-master-local.sh
install -m 0644 "$script_dir/jenkins.service.override.conf" \
  /etc/systemd/system/jenkins.service.d/override.conf
printf 'Environment="WAO_MASTER_PROJECT_ROOT=%s"\n' "$project_root" \
  >>/etc/systemd/system/jenkins.service.d/override.conf
printf 'Environment="WAO_JENKINS_SCM_BRANCH=%s"\n' "$jenkins_scm_branch" \
  >>/etc/systemd/system/jenkins.service.d/override.conf
if [ -n "$jenkins_proxy_port" ]; then
  printf '%s\n' \
    "Environment=\"HTTP_PROXY=http://127.0.0.1:${jenkins_proxy_port}\"" \
    "Environment=\"HTTPS_PROXY=http://127.0.0.1:${jenkins_proxy_port}\"" \
    'Environment="NO_PROXY=127.0.0.1,localhost,linux2.wayuwa.cn,linux1.wayuwa.cn,8.136.152.131"' \
    >>/etc/systemd/system/jenkins.service.d/override.conf
fi

install -m 0644 "$script_dir/wao-jenkins-webhook-tunnel.service" \
  /etc/systemd/system/wao-jenkins-webhook-tunnel.service
install -m 0644 "$script_dir/wao-jenkins-backup.service" \
  /etc/systemd/system/wao-jenkins-backup.service
install -m 0644 "$script_dir/wao-jenkins-backup.timer" \
  /etc/systemd/system/wao-jenkins-backup.timer
install -d -m 0700 /etc/wao-backup
install -d -m 0755 /usr/local/lib/wao-backup
for backup_script in \
  configure-offsite-backup.sh create-offsite-backup.sh restore-offsite-backup.sh \
  upload-peer-offsite.sh restore-peer-offsite.sh run-readable-content.sh \
  create-peer-backup.sh verify-peer-restore.sh peer-retention.py check-peer-backup.py; do
  install -m 0755 "$script_dir/../backup/$backup_script" \
    "/usr/local/lib/wao-backup/$backup_script"
done
for unit in wao-peer-backup.service wao-peer-backup.timer; do
  install -m 0644 "$script_dir/../backup/$unit" "/etc/systemd/system/$unit"
done
install -m 0755 "$script_dir/../backup/webdav_client.py" \
  /usr/local/lib/wao-backup/webdav_client.py
install -m 0755 "$script_dir/../backup/export-readable-content.py" \
  /usr/local/lib/wao-backup/export-readable-content.py
install -m 0755 "$script_dir/../backup/verify-backup-source.py" \
  /usr/local/lib/wao-backup/verify-backup-source.py
install -m 0755 "$script_dir/../storage/migrate_assets.py" \
  /usr/local/lib/wao-backup/migrate_assets.py
install -m 0755 "$script_dir/../storage/backfill_previews.py" \
  /usr/local/lib/wao-backup/backfill_previews.py
install -m 0755 "$script_dir/../storage/archive_original_png.py" \
  /usr/local/lib/wao-backup/archive_original_png.py
install -m 0755 "$script_dir/../storage/optimize_png_webp.py" \
  /usr/local/lib/wao-backup/optimize_png_webp.py
install -m 0755 "$script_dir/../storage/process-pending-images.sh" \
  /usr/local/lib/wao-backup/process-pending-images.sh
install -m 0755 "$script_dir/../storage/reclaim_mysql_blobs.py" \
  /usr/local/lib/wao-backup/reclaim_mysql_blobs.py
install -m 0755 "$script_dir/../storage/export_object_backup.py" \
  /usr/local/lib/wao-backup/export_object_backup.py
install -m 0755 "$script_dir/../storage/restore_object_backup.py" \
  /usr/local/lib/wao-backup/restore_object_backup.py
install -m 0644 "$script_dir/../storage/requirements.txt" \
  /usr/local/lib/wao-backup/storage-requirements.txt
install -m 0644 "$script_dir/../backup/wao-offsite-backup.service" \
  /etc/systemd/system/wao-offsite-backup.service
install -m 0644 "$script_dir/../backup/wao-offsite-backup.timer" \
  /etc/systemd/system/wao-offsite-backup.timer
for unit in wao-image-maintenance.service wao-image-maintenance.timer; do
  install -m 0644 "$script_dir/../storage/$unit" "/etc/systemd/system/$unit"
done

cat >/etc/jenkins/tunnel-ssh-config <<EOF
Host wao-cloud-webhook
  HostName 8.136.152.131
  User root
  IdentityFile /etc/jenkins/secrets/cloud-ed25519
  IdentitiesOnly yes
  UserKnownHostsFile /etc/jenkins/secrets/cloud-known-hosts
  StrictHostKeyChecking yes
EOF
chmod 0640 /etc/jenkins/tunnel-ssh-config

if [ ! -s "$plugin_manager.sha256" ]; then
  curl -fsSL \
    "https://github.com/jenkinsci/plugin-installation-manager-tool/releases/download/${plugin_manager_version}/jenkins-plugin-manager-${plugin_manager_version}.jar.sha256" \
    -o "$plugin_manager.sha256"
fi
expected_plugin_manager_sha="$(tr -d '\r\n' <"$plugin_manager.sha256")"
actual_plugin_manager_sha=''
if [ -s "$plugin_manager" ]; then
  actual_plugin_manager_sha="$(sha256sum "$plugin_manager" | awk '{print $1}')"
fi
if [ "$actual_plugin_manager_sha" != "$expected_plugin_manager_sha" ]; then
  curl -fsSL \
    "https://github.com/jenkinsci/plugin-installation-manager-tool/releases/download/${plugin_manager_version}/jenkins-plugin-manager-${plugin_manager_version}.jar" \
    -o "$plugin_manager.download"
  actual_plugin_manager_sha="$(sha256sum "$plugin_manager.download" | awk '{print $1}')"
  test "$actual_plugin_manager_sha" = "$expected_plugin_manager_sha"
  mv "$plugin_manager.download" "$plugin_manager"
fi

install -d -o jenkins -g jenkins -m 0750 "$jenkins_home/plugins"
install -d -o jenkins -g jenkins -m 0700 "$jenkins_home/.ssh"
install -o jenkins -g jenkins -m 0600 "$secret_dir/github-known-hosts" \
  "$jenkins_home/.ssh/known_hosts"
java -jar "$plugin_manager" \
  --war /usr/share/java/jenkins.war \
  --plugin-download-directory "$jenkins_home/plugins" \
  --plugin-file /etc/jenkins/plugins.txt \
  --latest=false

usermod -aG docker jenkins
usermod -aG jenkins "$deploy_owner"
chown -R jenkins:jenkins "$jenkins_home"
chown -R root:jenkins /etc/jenkins
chmod 0750 /etc/jenkins "$secret_dir"
find "$secret_dir" -type f ! -name '*.pub' -exec chmod 0640 {} +

test -d "$project_root" || {
  echo "WAO production directory does not exist: $project_root" >&2
  exit 1
}
setfacl -R -m u:jenkins:rwX "$project_root"
setfacl -R -m d:u:jenkins:rwX "$project_root"

cat >/etc/sudoers.d/wao-jenkins <<'EOF'
jenkins ALL=(wadi) NOPASSWD: /usr/local/lib/wao-jenkins/deploy-master-local.sh *
EOF
chmod 0440 /etc/sudoers.d/wao-jenkins
visudo -cf /etc/sudoers.d/wao-jenkins

systemctl daemon-reload
systemctl enable --now jenkins.service
systemctl enable --now wao-jenkins-backup.timer
systemctl enable --now wao-jenkins-health.timer
systemctl enable --now wao-offsite-backup.timer
systemctl enable --now wao-image-maintenance.timer
systemctl enable --now wao-jenkins-webhook-tunnel.service

printf '%s\n' \
  'Jenkins controller installed.' \
  'Open UI through: ssh -L 8081:127.0.0.1:8081 wadi@linux2.wayuwa.cn' \
  'Linux1 slave authorization key:'
cat "$secret_dir/slave-ed25519.pub"
printf '%s\n' 'Cloud authorization key:'
cat "$secret_dir/cloud-ed25519.pub"
