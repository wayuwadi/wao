#!/usr/bin/env python3
"""Resolve a real public IPv4 before opening the existing Linux1/Linux2 SSH tunnel."""
import argparse
import ipaddress
import json
import os
import socket
import urllib.parse
import urllib.request


def public_ipv4(value):
    try:
        address = ipaddress.ip_address(value)
        return str(address) if address.version == 4 and address.is_global else None
    except ValueError:
        return None


def resolve_ipv4(host):
    try:
        for entry in socket.getaddrinfo(host, 22, socket.AF_INET, socket.SOCK_STREAM):
            address = public_ipv4(entry[4][0])
            if address:
                return address
    except OSError:
        pass
    # TUN DNS can return 198.18.0.0/15 fake addresses. Ask authenticated HTTPS
    # resolvers for the real A record instead of pinning a changing WAN address.
    query = urllib.parse.urlencode({"name": host, "type": "A"})
    for resolver in ("https://dns.alidns.com/resolve", "https://dns.google/resolve"):
        try:
            with urllib.request.urlopen(resolver + "?" + query, timeout=8) as response:
                answer = json.load(response)
            if answer.get("Status") != 0:
                continue
            for record in answer.get("Answer", []):
                address = public_ipv4(record.get("data", "")) if record.get("type") == 1 else None
                if address:
                    return address
        except (OSError, ValueError):
            continue
    raise RuntimeError("No real public IPv4 is available for the tunnel host")


def ssh_command(host, address, identity, known_hosts, egress_port):
    return ["/usr/bin/ssh", "-NT", "-i", identity,
            "-o", "IPQoS=none", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o", "IdentitiesOnly=yes",
            "-o", "ExitOnForwardFailure=yes", "-o", "ServerAliveInterval=30",
            "-o", "ServerAliveCountMax=3", "-o", "StrictHostKeyChecking=yes",
            "-o", "UserKnownHostsFile=" + known_hosts, "-o", "HostKeyAlias=" + host,
            "-R", "127.0.0.1:2201:127.0.0.1:22",
            "-L", "127.0.0.1:17897:127.0.0.1:" + str(egress_port),
            "wao-tunnel@" + address]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="linux2.wayuwa.cn")
    parser.add_argument("--identity", required=True)
    parser.add_argument("--known-hosts", required=True)
    parser.add_argument("--egress-port", type=int, default=7897)
    args = parser.parse_args()
    if not 1 <= args.egress_port <= 65535:
        parser.error("egress port must be in 1..65535")
    command = ssh_command(args.host, resolve_ipv4(args.host), args.identity, args.known_hosts, args.egress_port)
    os.execv(command[0], command)


if __name__ == "__main__":
    main()
