#!/bin/sh
# Run in the host network namespace with NET_ADMIN; never print private config.
set -eu
iface=wg-wao
mark=0x6603
priority=8899
cleanup() {
  ip link delete "$iface" 2>/dev/null || true
  ip -4 rule del priority "$priority" fwmark "$mark" lookup main 2>/dev/null || true
  ip -6 rule del priority "$priority" fwmark "$mark" lookup main 2>/dev/null || true
}
case "${1:-}" in
  stop) cleanup; exit 0 ;;
  status) wg show "$iface" latest-handshakes; exit 0 ;;
  ensure)
    if ip link show "$iface" >/dev/null 2>&1; then
      test "$(wg show "$iface" public-key)" = "$(cat /config/public.key)"
      ip -4 address show dev "$iface" | grep -q '10.66.0.3/32'
      echo 'Existing owned private mesh interface verified.'
      exit 0
    fi ;;
  start) ;;
  *) echo 'Usage: linux1-mesh-interface.sh start|ensure|stop|status' >&2; exit 2 ;;
esac
if ip link show "$iface" >/dev/null 2>&1; then
  echo 'Interface already exists; refusing to overwrite its ownership/configuration.' >&2; exit 1
fi
for family in -4 -6; do
  if ip "$family" rule show | grep -q "^$priority:"; then
    echo 'Policy priority already in use; refusing to overwrite it.' >&2; exit 1
  fi
done
trap cleanup EXIT
ip link add "$iface" type wireguard
wg setconf "$iface" /config/wg-wao.conf
ip address add 10.66.0.3/32 dev "$iface"
ip link set mtu 1380 up dev "$iface"
ip route add 10.66.0.1/32 dev "$iface"
ip route add 10.66.0.2/32 dev "$iface"
ip route add 192.168.31.180/32 dev "$iface"
# Bypass the desktop TUN only for WireGuard's own encrypted UDP packets.
# Existing specific 10.66 routes win over the TUN's suppressed default route.
ip -4 rule add priority "$priority" fwmark "$mark" lookup main
ip -6 rule add priority "$priority" fwmark "$mark" lookup main
trap - EXIT
echo 'Private mesh interface installed; only two peer /32 routes added.'
