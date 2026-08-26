#!/bin/sh
set -eu

jenkins_url='http://127.0.0.1:8081'
backup_dir='/var/backups/jenkins'

if ! state="$(curl --globoff --fail --silent --show-error \
    -H 'X-Forwarded-User: wao-controller-backup' \
    -H 'X-Forwarded-Groups: wao-members' \
    "$jenkins_url/computer/api/json?tree=computer[executors[currentExecutable[url]],oneOffExecutors[currentExecutable[url]]]" 2>/dev/null)"; then
  echo 'Jenkins backup skipped: controller state could not be read.' >&2
  exit 0
fi
if printf '%s' "$state" | jq -e \
    '[.computer[] | (.executors[]?, .oneOffExecutors[]?) | select(.currentExecutable != null)] | length > 0' \
    >/dev/null; then
  echo 'Jenkins backup skipped: at least one executor is busy.'
  exit 0
fi

install -d -m 0700 "$backup_dir"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
archive="$backup_dir/jenkins-home-$stamp.tar.gz"

systemctl stop jenkins
restart_jenkins() { systemctl start jenkins; }
trap restart_jenkins EXIT HUP INT TERM
tar -C / -czf "$archive" \
  --exclude='var/lib/jenkins/workspace' \
  --exclude='var/lib/jenkins/caches' \
  --exclude='var/lib/jenkins/.m2' \
  --exclude='var/lib/jenkins/.npm' \
  var/lib/jenkins etc/jenkins
chmod 0600 "$archive"
sha256sum "$archive" >"$archive.sha256"
chmod 0600 "$archive.sha256"
restart_jenkins
trap - EXIT HUP INT TERM

find "$backup_dir" -maxdepth 1 -type f -name 'jenkins-home-*.tar.gz' \
  -printf '%T@ %p\n' | sort -nr | awk 'NR > 7 {print $2}' | while IFS= read -r old; do
    rm -f -- "$old" "$old.sha256"
  done
