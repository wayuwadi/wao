#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo 'Run configure-offsite-backup.sh as root.' >&2
  exit 1
fi

url="${1:?Usage: configure-offsite-backup.sh WEBDAV_URL USERNAME [REMOTE_ROOT]}"
username="${2:?123 Cloud Drive username is required}"
remote_root="${3:-WAO-backups}"
[[ "$url" == https://* ]] || { echo 'WebDAV URL must use HTTPS.' >&2; exit 2; }
for value in "$url" "$username" "$remote_root"; do
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || { echo 'Configuration values cannot contain newlines.' >&2; exit 2; }
done
[[ "$remote_root" =~ ^[A-Za-z0-9._/-]+$ ]] || { echo 'Remote root contains unsafe characters.' >&2; exit 2; }
case "/$remote_root/" in
  */../*|*/./*) echo 'Remote root cannot contain dot path segments.' >&2; exit 2 ;;
esac

read -r -s -p '123 云盘专用 WebDAV 应用密码：' webdav_password
printf '\n'
read -r -s -p '备份独立加密口令（至少 20 个字符）：' encryption_password
printf '\n'
read -r -s -p '再次输入加密口令：' encryption_confirmation
printf '\n'
[[ -n "$webdav_password" ]] || { echo 'WebDAV application password is empty.' >&2; exit 2; }
[[ ${#encryption_password} -ge 20 ]] || { echo 'Encryption passphrase is shorter than 20 characters.' >&2; exit 2; }
[[ "$encryption_password" == "$encryption_confirmation" ]] || { echo 'Encryption passphrases do not match.' >&2; exit 2; }

config_dir='/etc/wao-backup'
install -d -m 0700 "$config_dir"
config_tmp="$(mktemp)"
passphrase_tmp="$(mktemp)"
trap 'rm -f -- "$config_tmp" "$passphrase_tmp"' EXIT HUP INT TERM
printf '%s\n' \
  "WAO_BACKUP_WEBDAV_URL=$url" \
  "WAO_BACKUP_WEBDAV_USER=$username" \
  "WAO_BACKUP_WEBDAV_PASSWORD=$webdav_password" \
  "WAO_BACKUP_REMOTE_ROOT=$remote_root" >"$config_tmp"
printf '%s' "$encryption_password" >"$passphrase_tmp"
install -m 0600 "$config_tmp" "$config_dir/webdav.env"
install -m 0600 "$passphrase_tmp" "$config_dir/encryption-passphrase"

python3 /usr/local/lib/wao-backup/webdav_client.py \
  --config "$config_dir/webdav.env" check
systemctl enable --now wao-offsite-backup.timer
systemctl start wao-offsite-backup.service
systemctl --no-pager --full status wao-offsite-backup.service
printf '%s\n' \
  '123 云盘异地备份已配置，现有已验证的异机快照已上传。' \
  '请把加密口令另存到不依赖这两台 Linux 主机的密码管理器；丢失后备份无法恢复。'
