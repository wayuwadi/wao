#!/usr/bin/env python3
"""Start temporary application/gateway containers with discovery disabled; no published ports."""
import argparse
import json
import signal
import subprocess
import time
import uuid


def run(args, **kwargs):
    return subprocess.run(['docker', *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs).stdout


def inspect(name):
    return json.loads(run(['inspect', name]))[0]


def probe(container, port, path):
    return json.loads(run(['exec', container, 'curl', '-fsS', '--max-time', '4', f'http://127.0.0.1:{port}{path}']))


def main(role):
    suffix = uuid.uuid4().hex[:10]
    created = []
    try:
        source_app = inspect(f'wao-{role}-wao-server-1')
        source_gateway = inspect(f'wao-{role}-wao-gateway-1')
        shared = set(source_app['NetworkSettings']['Networks']) & set(source_gateway['NetworkSettings']['Networks'])
        if len(shared) != 1:
            raise RuntimeError('Expected one shared production application network')
        network = shared.pop()
        app = f'wao-recovery-app-{suffix}'
        gateway = f'wao-recovery-gateway-{suffix}'
        for source, name, port, memory in ((source_app, app, 18082, '2g'), (source_gateway, gateway, 19001, '1g')):
            env = dict(item.split('=', 1) for item in source['Config']['Env'])
            env.update(NACOS_DISCOVERY_ENABLED='false', NACOS_SERVER_ADDR='127.0.0.1:1', SERVER_PORT=str(port), GATEWAY_PORT=str(port), GATEWAY_SSL_ENABLED='false')
            if name == gateway:
                env['WAO_SERVER_URI'] = f'http://{app}:18082'
            if any('\n' in value or '\r' in value for value in env.values()):
                raise RuntimeError('Multiline environment needs a protected file-based configuration')
            # Pass the exact existing private environment through stdin, never command arguments or logs.
            args = ['create', '--name', name, '--network', network, '--memory', memory, '--cpus', '1', '--pids-limit', '512', '--env-file', '/dev/stdin']
            for host in source['HostConfig'].get('ExtraHosts') or []:
                args += ['--add-host', host]
            args.append(source['Image'])
            args += ['--wao.scheduling.enabled=false', '--spring.session.jdbc.cleanup-cron=-']
            run(args, input=('\n'.join(k+'='+v for k,v in env.items())+'\n').encode())
            created.append(name)
            run(['start', name])
            ready = False
            for _ in range(90):
                if inspect(name)['State']['Status'] != 'running':
                    raise RuntimeError('Recovery-mode process exited before readiness: ' + name)
                try:
                    if probe(name, port, '/actuator/health').get('status') == 'UP':
                        ready = True
                        break
                except (subprocess.CalledProcessError, ValueError):
                    pass
                time.sleep(2)
            if not ready:
                raise RuntimeError('Isolated recovery-mode process did not become healthy: ' + name)
            print(name.split('-'+suffix)[0] + ' healthy with discovery disabled', flush=True)
        readiness = probe(gateway, 19001, '/api/edge/ready')
        if readiness.get('code') != 0:
            raise RuntimeError('Gateway did not reach the application readiness endpoint')
        print('Recovery-mode startup and gateway-to-application database readiness passed; no public ports or user mutations.', flush=True)
    finally:
        for name in reversed(created):
            run(['rm', '-f', name])


if __name__ == '__main__':
    def stop(_signal, _frame):
        raise SystemExit('Recovery verification interrupted; cleaning temporary containers')
    signal.signal(signal.SIGTERM, stop)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('role', choices=('master', 'slave'))
    args = parser.parse_args()
    try:
        main(args.role)
    except subprocess.CalledProcessError:
        raise SystemExit('Docker recovery verification failed; private environment and command output suppressed.') from None
