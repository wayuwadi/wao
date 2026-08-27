#!/bin/sh
set -eu

private_env="${1:?private environment path is required}"
test -f "$private_env"

existing="$(sed -n 's/^JUMPSERVER_SSO_SHARED_SECRET=//p' "$private_env" | tail -n 1)"
if [ -n "$existing" ]; then
  case "$existing" in
    *[!A-Za-z0-9_-]*|'') echo 'Existing JumpServer SSO secret has an invalid format.' >&2; exit 1 ;;
  esac
  if [ "${#existing}" -lt 32 ]; then
    echo 'Existing JumpServer SSO secret is too short.' >&2
    exit 1
  fi
  exit 0
fi

umask 077
secret="$(openssl rand -hex 32)"
printf '\nJUMPSERVER_SSO_ENABLED=true\nJUMPSERVER_SSO_SHARED_SECRET=%s\n' "$secret" >>"$private_env"
echo 'JumpServer SSO secret generated in the private production environment.'
