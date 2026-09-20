#!/usr/bin/env bash
set -euo pipefail
umask 077
root="${WAO_PEER_SNAPSHOT_ROOT:-$HOME/backups/wao-peer}"
passphrase="${WAO_PEER_PASSPHRASE:-$HOME/.config/wao-backup/peer-passphrase}"
exec 9>"$root/.restore.lock"
flock -n 9 || exit 1
snapshot="$(find "$root" -mindepth 2 -maxdepth 2 -type f -name COMPLETE -printf '%h\n' | sort | tail -1)"
[[ "$(basename "$snapshot")" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || exit 1
if bash "$(dirname "$0")/verify-peer-restore.sh" "$snapshot" "$passphrase"; then
  date -u +%Y-%m-%dT%H:%M:%SZ >"$root/last-restore-success.tmp"
  mv "$root/last-restore-success.tmp" "$root/last-restore-success"
  rm -f "$root/RESTORE_FAILED"
else
  date -u +%Y-%m-%dT%H:%M:%SZ >"$root/RESTORE_FAILED"
  exit 1
fi
