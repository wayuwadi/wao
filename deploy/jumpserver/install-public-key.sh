#!/bin/sh
set -eu

public_key_file="${1:?public key file is required}"
test -s "$public_key_file"
key="$(sed -n '1p' "$public_key_file")"
case "$key" in ssh-ed25519\ *) ;; *) echo 'Invalid JumpServer public key.' >&2; exit 2 ;; esac

install -d -m 0700 "$HOME/.ssh"
touch "$HOME/.ssh/authorized_keys"
chmod 0600 "$HOME/.ssh/authorized_keys"
grep -qxF "$key" "$HOME/.ssh/authorized_keys" || printf '%s\n' "$key" >>"$HOME/.ssh/authorized_keys"
echo 'JumpServer dedicated public key installed.'
