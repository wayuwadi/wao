#!/usr/bin/env python3
"""Refresh only the authenticated peer's direct IPv6 endpoint; no relay/default route."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import urllib.parse
import urllib.request


def select_ipv6(addresses, previous):
    valid = sorted({str(ipaddress.ip_address(value)) for value in addresses
                    if ipaddress.ip_address(value).version == 6 and ipaddress.ip_address(value).is_global})
    if not valid:
        raise ValueError('No global IPv6 answer; preserving existing peer')
    return previous if previous in valid else valid[0]


def replace_endpoint(config, public_key, endpoint):
    blocks = re.split(r'(?m)^\[Peer\]\s*$', config)
    matched = 0
    for i in range(1, len(blocks)):
        if re.search(r'(?m)^PublicKey\s*=\s*' + re.escape(public_key) + r'\s*$', blocks[i]):
            if not re.search(r'(?m)^Endpoint\s*=', blocks[i]):
                raise ValueError('Expected an existing peer endpoint')
            blocks[i] = re.sub(r'(?m)^Endpoint\s*=.*$', 'Endpoint = ' + endpoint, blocks[i])
            matched += 1
    if matched != 1:
        raise ValueError('Expected exactly one matching peer')
    return '[Peer]'.join(blocks)


def resolve_ipv6(host):
    try:
        answers = [row[4][0] for row in socket.getaddrinfo(host, 0, socket.AF_INET6, socket.SOCK_DGRAM)]
        select_ipv6(answers, '')
        return answers
    except (OSError, ValueError):
        pass
    # The Linux1 desktop TUN can return synthetic DNS addresses. Only accept global
    # IPv6 from a certificate-verified resolver; never replace the authenticated key.
    query = urllib.parse.urlencode({'name': host, 'type': 'AAAA'})
    with urllib.request.urlopen('https://dns.alidns.com/resolve?' + query, timeout=8) as response:
        result = json.load(response)
    answers = [row['data'] for row in result.get('Answer', []) if row.get('type') == 28]
    select_ipv6(answers, '')
    return answers


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--role', choices=['linux1', 'linux2'], required=True)
    args = parser.parse_args()
    home = Path.home()
    root = home / '.config/wao-wireguard' if args.role == 'linux1' else Path('/etc/wao-node-mesh')
    metadata = root / 'public-topology.json'
    topology = json.loads(metadata.read_text())
    peer = 'linux2' if args.role == 'linux1' else 'linux1'
    identity = topology[peer]
    answers = resolve_ipv6(peer + '.wayuwa.cn')
    address = select_ipv6(answers, identity['ipv6'])
    prefix = (['docker', 'run', '--rm', '--network', 'host', '--cap-drop', 'ALL', '--cap-add', 'NET_ADMIN',
               'wao-wireguard-tools:1', 'wg'] if args.role == 'linux1' else ['wg'])
    interface = 'wg-wao' if args.role == 'linux1' else 'wg0'
    lines = subprocess.check_output(prefix + ['show', interface, 'latest-handshakes']).decode().splitlines()
    handshakes = dict(line.split() for line in lines)
    if address == identity['ipv6'] and time.time() - int(handshakes.get(identity['publicKey'], '0')) < 180:
        print('Direct peer handshake is fresh; endpoint unchanged.')
        return
    endpoint = '[' + address + ']:' + str(identity['port'])
    subprocess.run(prefix + ['set', interface, 'peer', identity['publicKey'], 'endpoint', endpoint], check=True)
    if args.role == 'linux1':
        path = root / 'wg-wao.conf'
        temporary = root / 'wg-wao.conf.tmp'
        temporary.write_text(replace_endpoint(path.read_text(), identity['publicKey'], endpoint))
        temporary.chmod(0o600)
        temporary.replace(path)
    else:
        subprocess.run(['wg-quick', 'save', interface], check=True, stdout=subprocess.DEVNULL)
    identity['ipv6'] = address
    temporary = metadata.with_suffix('.tmp')
    temporary.write_text(json.dumps(topology))
    os.chmod(temporary, 0o600)
    temporary.replace(metadata)
    print('Direct peer endpoint refreshed; authenticated identity and allowed routes unchanged.')


if __name__ == '__main__':
    main()
