#!/usr/bin/env python3
"""Probe the running Jenkins JVM; recover only repeated spawn-helper failures."""
import argparse
import fcntl
import http.cookiejar
import json
import os
from pathlib import Path
import time
import urllib.parse
import urllib.request

PROBE = r'''
import groovy.json.JsonOutput
import jenkins.model.Jenkins
import hudson.model.Job
import java.util.concurrent.TimeUnit

def j = Jenkins.get()
def spawn = 'ok'
try {
    def p = new ProcessBuilder('/usr/bin/true').start()
    if (!p.waitFor(5, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        spawn = 'timeout'
    } else if (p.exitValue() != 0) { spawn = 'nonzero' }
} catch (IOException e) {
    spawn = e.toString().contains('spawn helper') ? 'spawn_helper_failed' : 'process_start_failed'
}
def running = j.getAllItems(Job.class).collectMany { job ->
    job.getBuilds().findAll { it.isBuilding() }.collect {
        [job: job.fullName, number: it.number, since: it.startTimeInMillis]
    }
}
def queued = j.queue.items.collect {
    [id: it.id, job: it.task.name, since: it.inQueueSince, reason: it.why]
}
def pollingFailures = []
def mainJob = j.getItemByFullName('wao-main-cd')
if (mainJob != null) {
    ['github-polling.log', 'polling.log'].each { name ->
        def file = new File(mainJob.rootDir, name)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 600000) {
            def reader = new RandomAccessFile(file, 'r')
            try {
                reader.seek(Math.max(0L, reader.length() - 65536L))
                def bytes = new byte[(int)(reader.length() - reader.filePointer)]
                reader.readFully(bytes)
                if (new String(bytes, 'UTF-8').contains('ERROR:')) { pollingFailures.add(name) }
            } finally { reader.close() }
        }
    }
}
println JsonOutput.toJson([spawn: spawn, quietingDown: j.isQuietingDown(), running: running, queue: queued, pollingFailures: pollingFailures])
'''


class Jenkins:
    def __init__(self):
        # Never send trusted identity headers through the machine's outbound proxy.
        self.base = 'http://127.0.0.1:8081/jenkins'
        self.client = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.headers = {'X-Forwarded-User': 'wao-controller-health',
                        'X-Forwarded-Groups': 'wao-admins'}
        self.crumb = None

    def request(self, path, form=None):
        headers = dict(self.headers)
        if form is not None:
            if self.crumb is None:
                self.crumb = json.loads(self.request('/crumbIssuer/api/json'))
            headers[self.crumb['crumbRequestField']] = self.crumb['crumb']
        data = None if form is None else urllib.parse.urlencode(form).encode()
        request = urllib.request.Request(self.base + path, data=data, headers=headers)
        with self.client.open(request, timeout=20) as response:
            return response.read().decode()

    def probe(self):
        return json.loads(self.request('/scriptText', {'script': PROBE}))

    def restart(self):
        self.request('/safeRestart', {})


def evaluate(probe, previous, now, recover):
    """Pure recovery policy: no restart for HTTP errors, busy jobs or cooldown."""
    result = dict(probe, checkedAt=now, action='none', issues=[],
                  consecutiveSpawnFailures=0,
                  lastRestartAt=previous.get('lastRestartAt', 0))
    if probe['spawn'] != 'ok':
        result['issues'].append(probe['spawn'])
    for item in probe['queue']:
        if now - item['since'] / 1000 > 900:
            result['issues'].append('queue_over_15m:' + item['job'])
    for run in probe['running']:
        if now - run['since'] / 1000 > 3600:
            result['issues'].append('build_over_60m:' + run['job'])
    for log in probe.get('pollingFailures', []):
        result['issues'].append('scm_polling_failed:' + log)
    if probe['quietingDown']:
        result['issues'].append('quieting_down')
    if probe['spawn'] == 'spawn_helper_failed':
        recent = now - previous.get('checkedAt', 0) < 300
        count = previous.get('consecutiveSpawnFailures', 0) if recent else 0
        result['consecutiveSpawnFailures'] = count + 1
        if recover and count >= 1:
            if probe['running'] or probe['quietingDown']:
                result['action'] = 'deferred_busy'
            elif now - result['lastRestartAt'] < 1800:
                result['action'] = 'deferred_cooldown'
            else:
                result['action'] = 'safe_restart'
    return result


def check(client, previous, now, recover):
    try:
        result = evaluate(client.probe(), previous, now, recover)
        if result['action'] == 'safe_restart':
            # Persist the attempt BEFORE sending: a dropped response must not
            # produce a restart loop. main() writes this state before the POST.
            result['lastRestartAt'] = now
        return result
    except Exception as exc:
        # Do not log response bodies, cookie headers or raw server errors.
        return {'checkedAt': now, 'action': 'none',
                'issues': ['probe_unavailable:' + type(exc).__name__],
                'consecutiveSpawnFailures': 0,
                'lastRestartAt': previous.get('lastRestartAt', 0)}


def save(path, result):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    os.replace(temporary, path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recover', action='store_true')
    parser.add_argument('--state-dir', type=Path, default=Path('/var/lib/wao-jenkins-health'))
    args = parser.parse_args()
    args.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (args.state_dir / 'lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        path = args.state_dir / 'status.json'
        try:
            previous = json.loads(path.read_text())
        except (FileNotFoundError, ValueError):
            previous = {}
        client = Jenkins()
        result = check(client, previous, time.time(), args.recover)
        save(path, result)
        if result['action'] == 'safe_restart':
            try:
                client.restart()
                result['action'] = 'safe_restart_requested'
            except Exception as exc:
                result['action'] = 'restart_request_uncertain'
                result['issues'].append(type(exc).__name__)
            save(path, result)
        print(json.dumps(result, ensure_ascii=False))
        return 1 if result['issues'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
