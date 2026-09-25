#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo 'Image maintenance must run as root.' >&2
  exit 1
fi

project_root="${WAO_IMAGE_PROJECT_ROOT:-}"
if [[ -z "$project_root" && -f /etc/systemd/system/jenkins.service.d/override.conf ]]; then
  project_root="$(sed -n 's/^Environment="WAO_MASTER_PROJECT_ROOT=\(.*\)"$/\1/p' \
    /etc/systemd/system/jenkins.service.d/override.conf | tail -n 1)"
fi
project_root="${project_root:-/home/wadi/Desktop/deploy/wao}"
[[ "$project_root" == /* && "$project_root" != / && -f "$project_root/deploy/.env" ]] || {
  echo 'WAO deployment environment is missing.' >&2
  exit 1
}

umask 077

set -a
# Generated deployment file with shell-safe values.
# shellcheck disable=SC1090
. "$project_root/deploy/.env"
set +a

if [[ "${WAO_STORAGE_MODE:-mysql}" != s3 ]]; then
  echo 'Image maintenance skipped: WAO still uses MySQL storage.'
  exit 0
fi

asset_lock="${WAO_IMAGE_BACKUP_LOCK_FILE:-/run/lock/wao-image-backup.lock}"
exec 9>"$asset_lock"
flock -n 9 || { echo 'Image maintenance skipped: another image or backup run is active.'; exit 0; }

for key in DB_USER DB_PASS DB_NAME DB_ROOT_PASS MYSQL_WRITER_HOST \
  WAO_STORAGE_ENDPOINT WAO_STORAGE_BUCKET WAO_STORAGE_ACCESS_KEY \
  WAO_STORAGE_SECRET_KEY; do
  [[ -n "${!key:-}" ]] || { echo "Missing required image maintenance setting: $key" >&2; exit 1; }
done

python=/usr/local/lib/wao-backup/.venv/bin/python
script_root=/usr/local/lib/wao-backup
[[ -x "$python" && -f "$script_root/migrate_assets.py" \
    && -f "$script_root/backfill_previews.py" \
    && -f "$script_root/archive_original_png.py" \
    && -f "$script_root/optimize_png_webp.py" ]] || {
  echo 'Image maintenance runtime is incomplete.' >&2
  exit 1
}

apply=false
case "${1:-}" in
  '') ;;
  --apply) apply=true ;;
  *) echo 'Usage: process-pending-images.sh [--apply]' >&2; exit 2 ;;
esac

if [[ "$apply" == true ]]; then
  python3 "$script_root/verify-backup-source.py" >/dev/null || {
    echo 'WAO replica or writer provenance is not verified; image maintenance stopped.' >&2
    exit 1
  }
  arguments=(--apply --limit 100)
else
  arguments=(--limit 100)
fi

"$python" "$script_root/migrate_assets.py" "${arguments[@]}"
"$python" "$script_root/backfill_previews.py" "${arguments[@]}"
"$python" "$script_root/archive_original_png.py" "${arguments[@]}"
"$python" "$script_root/optimize_png_webp.py" "${arguments[@]}"
