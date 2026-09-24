#!/bin/sh
set -eu

host="${1:?Gateway hostname is required}"
# Cloud /etc/hosts maps Linux1 to a local SSH tunnel. That address is not a
# public Gateway and must never enter the public Nginx upstream.
address="$(getent ahostsv4 "$host" 2>/dev/null | awk '$2 == "STREAM" && $1 !~ /^127\./ { print $1; exit }')"
if [ -z "$address" ]; then
  address="$(dig +time=2 +tries=2 +short A "$host" @"${WAO_PUBLIC_DNS_RESOLVER:-223.5.5.5}" \
    | awk '$0 ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ && $0 !~ /^127\./ { print; exit }')"
fi
case "$address" in
  ''|*[!0-9.]*) echo "No public IPv4 address for $host" >&2; exit 1 ;;
esac
printf '%s\n' "$address"
