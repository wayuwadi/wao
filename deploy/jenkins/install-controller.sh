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

case "$jenkins_scm_branch" in
  ''|*[!A-Za-z0-9._/-]*)
    echo "Invalid Jenkins SCM branch: $jenkins_scm_branch" >&2
    exit 2
    ;;
esac

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
generate_key "$secret_dir/windows-ed25519"
generate_key "$secret_dir/cloud-ed25519"

for required in github-pat github-scm-ed25519 windows-known-hosts cloud-known-hosts; do
  if [ ! -s "$secret_dir/$required" ]; then
    printf 'Missing %s. Follow deploy/jenkins/README.md before installation.\n' \
      "$secret_dir/$required" >&2
    exit 1
  fi
done

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl fontconfig git jq openssh-client openssl \
  openjdk-21-jre acl sudo unzip zip

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
install -m 0755 "$script_dir/backup-controller.sh" /usr/local/lib/wao-jenkins/backup-controller.sh
install -m 0755 "$script_dir/deploy-master-local.sh" /usr/local/lib/wao-jenkins/deploy-master-local.sh
install -m 0644 "$script_dir/jenkins.service.override.conf" \
  /etc/systemd/system/jenkins.service.d/override.conf
printf 'Environment="WAO_MASTER_PROJECT_ROOT=%s"\n' "$project_root" \
  >>/etc/systemd/system/jenkins.service.d/override.conf
printf 'Environment="WAO_JENKINS_SCM_BRANCH=%s"\n' "$jenkins_scm_branch" \
  >>/etc/systemd/system/jenkins.service.d/override.conf

install -m 0644 "$script_dir/wao-jenkins-webhook-tunnel.service" \
  /etc/systemd/system/wao-jenkins-webhook-tunnel.service
install -m 0644 "$script_dir/wao-jenkins-backup.service" \
  /etc/systemd/system/wao-jenkins-backup.service
install -m 0644 "$script_dir/wao-jenkins-backup.timer" \
  /etc/systemd/system/wao-jenkins-backup.timer

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
systemctl enable --now wao-jenkins-webhook-tunnel.service

printf '%s\n' \
  'Jenkins controller installed.' \
  'Open UI through: ssh -L 8081:127.0.0.1:8081 wadi@linux2.wayuwa.cn' \
  'Windows authorization key:'
cat "$secret_dir/windows-ed25519.pub"
printf '%s\n' 'Cloud authorization key:'
cat "$secret_dir/cloud-ed25519.pub"
