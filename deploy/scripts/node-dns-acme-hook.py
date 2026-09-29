#!/usr/bin/env python3
"""Certbot DNS-01 hook, restricted to the current Linux2 public node."""
import base64
import datetime as dt
import hashlib
import hmac
import json
import os
from pathlib import Path
import shlex
import sys
import time
import urllib.parse
import urllib.request
import uuid


def api(action, **parameters):
    credentials = Path(os.environ.get('WAO_DNS_CREDENTIALS', '~/.config/wao-node-acme/credentials')).expanduser()
    values = {}
    for line in credentials.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            values[key.strip()] = shlex.split(value.strip())[0]
    request = dict(AccessKeyId=values['ALIBABA_CLOUD_ACCESS_KEY_ID'], Action=action,
                   Format='JSON', SignatureMethod='HMAC-SHA1', SignatureNonce=str(uuid.uuid4()),
                   SignatureVersion='1.0', Timestamp=dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                   Version='2015-01-09', **parameters)
    encode = lambda value: urllib.parse.quote(str(value), safe='~')
    canonical = '&'.join(f'{encode(key)}={encode(request[key])}' for key in sorted(request))
    signature = hmac.new((values['ALIBABA_CLOUD_ACCESS_KEY_SECRET'] + '&').encode(),
                         ('GET&%2F&' + encode(canonical)).encode(), hashlib.sha1).digest()
    request['Signature'] = base64.b64encode(signature).decode()
    try:
        with urllib.request.urlopen('https://alidns.aliyuncs.com/?' + urllib.parse.urlencode(request), timeout=20) as response:
            result = json.load(response)
    except Exception:
        raise RuntimeError(f'AliDNS {action} failed; credentials and signed URL suppressed') from None
    if 'Code' in result:
        raise RuntimeError(f'AliDNS {action} rejected request')
    return result


def main():
    os.umask(0o077)
    domain = os.environ['CERTBOT_DOMAIN']
    token = os.environ['CERTBOT_VALIDATION']
    if domain != 'linux2.wayuwa.cn':
        raise ValueError('Unexpected certificate domain')
    rr = '_acme-challenge.' + domain.split('.')[0]
    root = Path('~/.local/state/wao-node-acme').expanduser()
    root.mkdir(parents=True, exist_ok=True)
    state = root / (hashlib.sha256((domain + token).encode()).hexdigest() + '.json')
    if sys.argv[1] == 'auth':
        if state.exists():
            raise ValueError('An existing challenge needs cleanup first')
        result = api('AddDomainRecord', DomainName='wayuwa.cn', RR=rr, Type='TXT', Value=token, TTL='600')
        state.write_text(json.dumps({'id': result['RecordId'], 'rr': rr, 'token': token}))
        # Bounded propagation window. Certbot performs authoritative validation.
        time.sleep(45)
    elif sys.argv[1] == 'cleanup' and state.exists():
        previous = json.loads(state.read_text())
        current = api('DescribeDomainRecordInfo', RecordId=previous['id'])
        if current.get('RR') != rr or current.get('Type') != 'TXT' or current.get('Value') != token:
            raise ValueError('Refusing to remove a challenge record modified by another actor')
        api('DeleteDomainRecord', RecordId=previous['id'])
        state.unlink()
    elif sys.argv[1] != 'cleanup':
        raise ValueError('Expected auth or cleanup')


if __name__ == '__main__':
    main()
