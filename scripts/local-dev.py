"""Set up an independent local installation without importing production data."""
import argparse
import base64
import os
from pathlib import Path
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def initialize():
    destination = ROOT / '.env'
    values = {
        'MYSQL_HOST': '127.0.0.1', 'MYSQL_PORT': '3306', 'DB_NAME': 'wao',
        'DB_USER': 'wao_app', 'DB_PASS': secrets.token_hex(24), 'DB_ROOT_PASS': secrets.token_hex(24),
        'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': '6379', 'REDIS_PASS': secrets.token_hex(24),
        'WAO_VAULT_KEY': base64.b64encode(secrets.token_bytes(32)).decode(),
        'AGENT_CREDENTIAL_KEY': base64.b64encode(secrets.token_bytes(32)).decode(),
        'NACOS_SERVER_ADDR': '127.0.0.1:8848', 'NACOS_USERNAME': 'nacos', 'NACOS_PASSWORD': '',
        'NACOS_DISCOVERY_ENABLED': 'false', 'WAO_SERVER_URI': 'http://127.0.0.1:8080',
        'FEISHU_LOGIN_ENABLED': 'false', 'JUMPSERVER_SSO_ENABLED': 'false',
        'WAO_URL': 'http://localhost:9000/api/mcp/workspace',
    }
    with destination.open('x', encoding='utf-8') as stream:
        stream.write(''.join(f'{key}={value}\n' for key, value in values.items()))
    if os.name != 'nt': destination.chmod(0o600)
    print('Created private .env with independent random credentials. Keep this file out of Git.')

def run(component):
    environment = dict(os.environ)
    for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
        if line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            environment[key] = value
    commands = {
        'services': ('docker', ['compose', '-f', 'docker-compose-dev.yml', 'up', '-d'], ROOT),
        'server': ('mvn', ['-pl', 'wao-server', 'spring-boot:run', '-Dspring-boot.run.arguments=--spring.profiles.active=dev --server.address=127.0.0.1'], ROOT),
        'gateway': ('mvn', ['-pl', 'wao-gateway', 'spring-boot:run', '-Dspring-boot.run.arguments=--spring.profiles.active=dev --server.address=127.0.0.1'], ROOT),
        'web': ('npm', ['run', 'dev', '--', '--host', '127.0.0.1'], ROOT / 'wao-web'),
    }
    executable, arguments, directory = commands[component]
    resolved = shutil.which(executable)
    if not resolved: raise RuntimeError(f'Missing executable: {executable}')
    raise SystemExit(subprocess.call([resolved, *arguments], cwd=directory, env=environment))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'services', 'server', 'gateway', 'web'])
    arguments = parser.parse_args()
    if arguments.action == 'init': initialize()
    else: run(arguments.action)
