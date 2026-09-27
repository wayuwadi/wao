#!/bin/sh
set -eu

host="${1:?Gateway hostname is required}"
# Cloud /etc/hosts maps Linux1 to its reverse SSH tunnel. Use the address
# resolved on the cloud host so Nginx and health probes take the same route.
address="$(getent ahostsv4 "$host" 2>/dev/null | awk '$2 == "STREAM" { print $1; exit }')"
# The cloud host keeps Linux1's reverse SSH tunnel on loopback. Prefer its
# independent WireGuard route for Gateway traffic; keep loopback as Nginx's
# backup route. Other hosts continue to use their normal IPv4 resolution.
if [ "$host" = linux1.wayuwa.cn ] && [ "$address" = 127.0.0.1 ]; then
  address="${WAO_LINUX1_GATEWAY_MESH_IPV4:-10.66.0.3}"
fi
if [ -z "$address" ]; then
  address="$(dig +time=2 +tries=2 +short A "$host" @"${WAO_PUBLIC_DNS_RESOLVER:-223.5.5.5}" \
    | awk '$0 ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ { print; exit }')"
fi
case "$address" in
  ''|*[!0-9.]*) echo "No IPv4 address for $host" >&2; exit 1 ;;
esac
printf '%s\n' "$address"
