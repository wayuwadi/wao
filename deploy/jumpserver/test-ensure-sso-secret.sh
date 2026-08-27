#!/bin/sh
set -eu

work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT HUP INT TERM
private_env="$work_dir/private.env"
printf '%s\n' 'WAO_VAULT_KEY=test-only' >"$private_env"

sh deploy/jumpserver/ensure-sso-secret.sh "$private_env" >/dev/null
first_secret="$(sed -n 's/^JUMPSERVER_SSO_SHARED_SECRET=//p' "$private_env")"
test "${#first_secret}" = 64
test "$(grep -c '^JUMPSERVER_SSO_SHARED_SECRET=' "$private_env")" = 1
test "$(grep -c '^JUMPSERVER_SSO_ENABLED=true$' "$private_env")" = 1

sh deploy/jumpserver/ensure-sso-secret.sh "$private_env" >/dev/null
test "$(sed -n 's/^JUMPSERVER_SSO_SHARED_SECRET=//p' "$private_env")" = "$first_secret"
test "$(grep -c '^JUMPSERVER_SSO_SHARED_SECRET=' "$private_env")" = 1

printf '%s\n' 'JUMPSERVER_SSO_SHARED_SECRET=short' >"$work_dir/invalid.env"
if sh deploy/jumpserver/ensure-sso-secret.sh "$work_dir/invalid.env" >/dev/null 2>&1; then
  echo 'Short JumpServer SSO secret was unexpectedly accepted.' >&2
  exit 1
fi

echo 'JumpServer SSO secret generation tests passed.'
