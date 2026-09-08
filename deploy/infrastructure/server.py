#!/usr/bin/env python3
"""Loopback-only collector API. Nginx authenticates administrators; no remote commands from HTTP."""
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import threading
import time
from urllib.parse import parse_qs, urlsplit

DATA = Path(os.environ.get('WAO_INFRA_DATA', '/var/lib/wao-infrastructure'))
CONFIG = Path(os.environ.get('WAO_INFRA_CONFIG', '/etc/wao-infrastructure/nodes.json'))
PROBE = Path(__file__).with_name('probe.py')
LOCK = threading.Lock()
NODES = []
INTERVAL = 60


def connection():
    return sqlite3.connect(DATA / 'metrics.sqlite', timeout=10)


def initialize():
    DATA.mkdir(parents=True, exist_ok=True)
    with connection() as db:
        db.execute('CREATE TABLE IF NOT EXISTS metric(node TEXT, ts INTEGER, cpu REAL, available INTEGER, total INTEGER, disk INTEGER, swap INTEGER, PRIMARY KEY(node,ts))')
    try:
        return json.loads((DATA / 'snapshot.json').read_text())
    except (OSError, ValueError):
        return {}


LATEST = {}


def sample(node):
    try:
        completed = subprocess.run(node['command'], input=PROBE.read_text(), text=True, capture_output=True, timeout=40)
        if completed.returncode != 0:
            raise ValueError('采集连接或读取失败')
        if len(completed.stdout) > 512000:
            raise ValueError('采集数据过大')
        value = json.loads(completed.stdout)
        if not isinstance(value.get('services'), list) or not all(k in value for k in ['collectedAt', 'cpuPercent', 'memory', 'disks']):
            raise ValueError('采集数据格式异常')
        return node['id'], value, None
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return node['id'], None, '无法获取本次数据，请检查节点连接和采集权限'


def service_deltas(current, previous):
    elapsed = current['collectedAt'] - previous.get('collectedAt', current['collectedAt'])
    old = {(s['kind'], s['name']): s for s in previous.get('services', [])}
    for item in current['services']:
        earlier = old.get((item['kind'], item['name']), {})
        now_cpu, old_cpu = item.get('cpuTotalNs'), earlier.get('cpuTotalNs')
        if elapsed > 0 and now_cpu is not None and old_cpu is not None and now_cpu >= old_cpu:
            item['cpuPercent'] = round((now_cpu - old_cpu) / (elapsed * 1e9) * 100, 2)


def collect_once():
    with ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(sample, NODES))
    with LOCK:
        for node_id, value, error in results:
            previous = LATEST.get(node_id, {})
            if value is not None:
                service_deltas(value, previous)
                value['lastAttemptAt'] = int(time.time())
                value['error'] = None
                LATEST[node_id] = value
                mem = value['memory']
                root = next((d for d in value['disks'] if d['mount'] == '/'), {})
                with connection() as db:
                    db.execute('INSERT OR REPLACE INTO metric VALUES(?,?,?,?,?,?,?)',
                               (node_id, value['collectedAt'], value['cpuPercent'], mem['available'], mem['total'], root.get('available'), mem['swapUsed']))
                    db.execute('DELETE FROM metric WHERE ts < ?', (int(time.time()) - 7 * 86400,))
            else:
                LATEST[node_id] = {**previous, 'lastAttemptAt': int(time.time()), 'error': error}
        temp = DATA / 'snapshot.tmp'
        temp.write_text(json.dumps(LATEST, ensure_ascii=False))
        os.chmod(temp, 0o600)
        temp.replace(DATA / 'snapshot.json')

def collect_loop():
    while True:
        started = time.monotonic()
        try:
            collect_once()
        except Exception:
            # Keep HTTP alive so last successful snapshots become explicitly stale.
            print('Infrastructure collection failed; retained last snapshot', flush=True)
        time.sleep(max(1, INTERVAL - (time.monotonic() - started)))


def overview(hours):
    now = int(time.time())
    bucket = max(60, hours * 3600 // 240)
    with LOCK:
        nodes = [{k: n[k] for k in ['id', 'name', 'role', 'address']} | dict(LATEST.get(n['id'], {})) for n in NODES]
    with connection() as db:
        for node in nodes:
            node['stale'] = now - node.get('collectedAt', 0) > 180
            node['online'] = not node['stale'] and not node.get('error')
            points = db.execute('SELECT MIN(ts),AVG(cpu),AVG(available),AVG(total),MIN(disk),MAX(swap) FROM metric WHERE node=? AND ts>=? GROUP BY CAST(ts / ? AS INTEGER) ORDER BY MIN(ts)',
                                (node['id'], now - hours * 3600, bucket)).fetchall()
            node['history'] = [{'ts': p[0], 'cpu': p[1], 'memoryAvailable': p[2], 'memoryTotal': p[3], 'diskAvailable': p[4], 'swapUsed': p[5]} for p in points]
    return {'generatedAt': now, 'intervalSeconds': INTERVAL, 'historyHours': hours, 'nodes': nodes}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlsplit(self.path)
        if parsed.path not in ['/overview', '/health']:
            self.send_error(404)
            return
        try:
            hours = int(parse_qs(parsed.query).get('hours', ['24'])[0])
            if hours not in [1, 24, 168]: raise ValueError()
        except ValueError:
            self.send_error(400)
            return
        result = {'status': 'ok'} if parsed.path == '/health' else {'code': 0, 'message': 'ok', 'data': overview(hours), 'timestamp': int(time.time() * 1000)}
        body = json.dumps(result, ensure_ascii=False, allow_nan=False).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    NODES = json.loads(CONFIG.read_text())
    LATEST = initialize()
    threading.Thread(target=collect_loop, daemon=True).start()
    ThreadingHTTPServer(('127.0.0.1', 18782), Handler).serve_forever()
