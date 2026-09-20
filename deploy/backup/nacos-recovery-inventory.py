#!/usr/bin/env python3
"""Compare namespace/config content via an authenticated isolated-container probe.

Passwords and access tokens remain in process memory / curl stdin. Output contains
only counts and a digest; never configuration values or authentication material.
"""
import argparse
import hashlib
import json
import subprocess
import urllib.parse


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', required=True)
    parser.add_argument('--credential-container', required=True)
    args = parser.parse_args()
    env = dict(value.split('=', 1) for value in json.loads(subprocess.check_output(
        ['docker', 'inspect', args.credential_container, '--format', '{{json .Config.Env}}'])))

    def call(path, data=None, token=None):
        command = ['docker', 'exec', '-i', args.container, 'curl', '-fsS', '--max-time', '15']
        if token:
            # The token is an HTTP header read from stdin, never a URL/argv/log value.
            command += ['--config', '-']
            payload = ('header = "accessToken: ' + token + '"\n').encode()
        elif data is not None:
            command += ['--data-binary', '@-']
            payload = urllib.parse.urlencode(data).encode()
        else:
            payload = b''
        command += ['http://127.0.0.1:8848/nacos' + path]
        result = subprocess.run(command, input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError('Nacos recovery probe failed; no response body logged')
        return json.loads(result.stdout)

    token = call('/v1/auth/login', {'username': env['NACOS_USERNAME'],
                                  'password': env['NACOS_PASSWORD']})['accessToken']
    namespaces = call('/v1/console/namespaces', token=token)['data']
    records = []
    config_count = 0
    for ns in namespaces:
        tenant = ns['namespace']
        page = 1
        while True:
            query = urllib.parse.urlencode({'search': 'accurate', 'dataId': '', 'group': '',
                                            'tenant': tenant, 'pageNo': page, 'pageSize': 100})
            listing = call('/v1/cs/configs?' + query, token=token)
            for row in listing['pageItems']:
                if not isinstance(row.get('content'), str):
                    raise RuntimeError('Configuration listing omitted content; cannot verify restoration')
                records.append([tenant, row['dataId'], row['group'], row['content']])
                config_count += 1
            if page >= listing['pagesAvailable']:
                break
            page += 1
    canonical = json.dumps({'namespaces': sorted((n['namespace'], n['namespaceShowName']) for n in namespaces),
                            'configs': sorted(records)}, ensure_ascii=False, sort_keys=True).encode()
    print(json.dumps({'authenticated': True, 'namespaces': len(namespaces), 'configs': config_count,
                      'configuration_sha256': hashlib.sha256(canonical).hexdigest()}, sort_keys=True))


if __name__ == '__main__':
    main()
