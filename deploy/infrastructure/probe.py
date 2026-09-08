#!/usr/bin/env python3
"""Read-only Linux inventory. Emits bounded metrics, never environment or command lines."""
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time


def run(args, timeout=12):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return result.stdout if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def cpu_ticks():
    values = [int(x) for x in Path('/proc/stat').read_text().splitlines()[0].split()[1:9]]
    return sum(values), values[3] + values[4]


def memory():
    values = {}
    for line in Path('/proc/meminfo').read_text().splitlines():
        key, value = line.split(':', 1)
        values[key] = int(value.strip().split()[0]) * 1024
    return {'total': values['MemTotal'], 'available': values.get('MemAvailable', values['MemFree']),
            'swapTotal': values.get('SwapTotal', 0), 'swapUsed': values.get('SwapTotal', 0) - values.get('SwapFree', 0)}


def bytes_value(value):
    match = re.fullmatch(r'([\d.]+)\s*([kmgt]?i?b)', value.strip().lower())
    if not match:
        return None
    unit = match[2]
    power = {'b': 0, 'k': 1, 'm': 2, 'g': 3, 't': 4}.get(unit[0], 0)
    return int(float(match[1]) * (1024 if 'i' in unit else 1000) ** power)


def group(name, image=''):
    value = (name + ' ' + image).lower()
    if 'weknora' in value: return 'WeKnora'
    if 'jms_' in value or 'jumpserver/' in value: return 'JumpServer'
    if 'jenkins' in value: return 'Jenkins'
    if (name.startswith('docker-') and not name.endswith('.service')) or 'langgenius/' in value: return 'Dify'
    if 'nacos' in value: return 'Nacos'
    if any(x in value for x in ['grafana', 'prometheus', 'loki', 'tempo', 'promtail']): return '监控组件'
    if name.startswith(('wao-', 'wayuwa-')): return 'WAO'
    if any(x in value for x in ['mysql', 'redis']): return '数据库'
    if 'nginx' in value: return 'Nginx'
    return '其他服务'


def containers():
    listing = run(['docker', 'ps', '-a', '--format', '{{json .}}'])
    if listing is None:
        return [], 'Docker 未安装或当前采集账号无读取权限'
    stats_raw = run(['docker', 'stats', '--no-stream', '--format', '{{json .}}'])
    stats = {}
    for line in (stats_raw or '').splitlines():
        value = json.loads(line)
        stats[value['Name']] = value
    result = []
    for line in listing.splitlines()[:200]:
        value = json.loads(line)
        name = value['Names']
        stat = stats.get(name, {})
        mem = stat.get('MemUsage', '').split('/')
        cpu = stat.get('CPUPerc', '').rstrip('%')
        result.append({'name': name, 'kind': 'docker', 'group': group(name, value['Image']),
                       'image': value['Image'], 'ports': value.get('Ports', ''), 'state': value.get('State', 'unknown'), 'status': value['Status'],
                       'cpuPercent': float(cpu) if cpu else None,
                       'memoryBytes': bytes_value(mem[0]) if mem else None,
                       'memoryLimitBytes': bytes_value(mem[1]) if len(mem) == 2 else None,
                       'resourceScope': 'container'})
    return result, None if stats_raw is not None else '容器清单可读，资源统计暂不可用'


def system_services(user=False):
    base = ['systemctl'] + (['--user'] if user else [])
    raw = run(base + ['list-units', '--type=service', '--all', '--no-pager', '--no-legend'])
    if raw is None: return []
    names = []
    for line in raw.splitlines():
        parts = line.lstrip('● ').split()
        if len(parts) < 4 or not re.fullmatch(r'[A-Za-z0-9_.@:\\-]+\.service', parts[0]): continue
        if parts[2] == 'active' or any(k in parts[0] for k in ['wao-', 'jenkins', 'nginx', 'mysqld']):
            names.append(parts[0])
    if not names: return []
    raw = run(base + ['show', *names[:150], '--no-pager', '--property=Id,ActiveState,SubState,MemoryCurrent,CPUUsageNSec,MainPID'])
    result = []
    for block in (raw or '').strip().split('\n\n'):
        value = dict(line.split('=', 1) for line in block.splitlines() if '=' in line)
        name = value.get('Id')
        if not name: continue
        mem = value.get('MemoryCurrent', '')
        usage = value.get('CPUUsageNSec', '')
        result.append({'name': name, 'kind': 'user-service' if user else 'systemd', 'group': group(name),
                       'image': '', 'state': value.get('ActiveState'), 'status': value.get('SubState'),
                       'memoryBytes': int(mem) if mem.isdigit() and int(mem) < 2**63 else None,
                       'cpuTotalNs': int(usage) if usage.isdigit() and int(usage) < 2**63 else None,
                       'cpuPercent': None, 'resourceScope': 'cgroup'})
    return result


def collect():
    before = cpu_ticks()
    time.sleep(0.25)
    after = cpu_ticks()
    elapsed = after[0] - before[0]
    cpu = round(100 * (1 - (after[1] - before[1]) / elapsed), 1) if elapsed else None
    disks = []
    raw = run(['df', '-P', '-B1', '-x', 'tmpfs', '-x', 'devtmpfs', '-x', 'overlay', '-x', 'efivarfs', '-x', 'fuse.gvfsd-fuse'])
    for line in (raw or '').splitlines()[1:]:
        parts = line.split()
        if len(parts) != 6 or not parts[1].isdigit(): continue
        disks.append({'mount': parts[5], 'total': int(parts[1]), 'used': int(parts[2]), 'available': int(parts[3])})
    services, warning = containers()
    services += system_services() + system_services(user=True)
    os_info = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    return {'hostname': socket.gethostname(), 'os': os_info.get('PRETTY_NAME', 'Linux').strip('"'),
            'cpuCores': os.cpu_count(), 'cpuPercent': cpu, 'memory': memory(), 'disks': disks,
            'loadAverage': list(os.getloadavg()), 'uptimeSeconds': int(float(Path('/proc/uptime').read_text().split()[0])),
            'services': services, 'warnings': [warning] if warning else [], 'collectedAt': int(time.time())}


if __name__ == '__main__':
    print(json.dumps(collect(), ensure_ascii=False))
