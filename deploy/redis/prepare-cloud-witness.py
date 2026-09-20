#!/usr/bin/env python3
"""Run as Linux2 administrator after installing the masked distro Sentinel package.

Write a private witness config with quorum=4; do not start/enable it here.
Existing configuration is never overwritten. Credentials travel over SSH stdin.
"""
import json
import subprocess


def main():
    result = subprocess.run(["docker", "inspect", "wao-master-redis-master-1"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    env = dict(item.split("=", 1) for item in json.loads(result.stdout)[0]["Config"]["Env"] if "=" in item)
    password = env["REDIS_PASS"]
    assert password
    config = f'''bind 127.0.0.1 10.66.0.1
port 26379
protected-mode yes
dir /var/lib/wao-sentinel
requirepass {json.dumps(password, ensure_ascii=False)}
sentinel announce-ip 10.66.0.1
sentinel announce-port 26379
sentinel monitor wao-master 192.168.31.180 6379 4
sentinel auth-pass wao-master {json.dumps(password, ensure_ascii=False)}
sentinel down-after-milliseconds wao-master 10000
sentinel failover-timeout wao-master 60000
sentinel parallel-syncs wao-master 1
'''
    command = ["runuser", "-u", "jenkins", "--", "ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
               "-i", "/etc/jenkins/secrets/cloud-ed25519", "-o",
               "UserKnownHostsFile=/etc/jenkins/secrets/cloud-known-hosts", "root@8.136.152.131",
               "set -e; test ! -e /var/lib/wao-sentinel/sentinel.conf; "
               "install -d -m 700 -o redis -g redis /var/lib/wao-sentinel; "
               "umask 077; cat > /var/lib/wao-sentinel/sentinel.conf.tmp; "
               "chown redis:redis /var/lib/wao-sentinel/sentinel.conf.tmp; "
               "mv /var/lib/wao-sentinel/sentinel.conf.tmp /var/lib/wao-sentinel/sentinel.conf"]
    completed = subprocess.run(command, input=config.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if completed.returncode:
        raise RuntimeError("Private witness configuration could not be prepared; no automatic overwrite")
    print("Cloud witness configured with quorum=4, not started")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Cloud witness preparation failed; inspect locally without exporting credentials")
        raise SystemExit(1)
