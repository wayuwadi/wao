#!/usr/bin/env python3
"""Gate failover first on BOTH nodes, then adopt private endpoints one node at a time."""
import argparse
import importlib.util
from pathlib import Path
import subprocess

spec = importlib.util.spec_from_file_location("ha", Path(__file__).with_name("validate-private-ha.py"))
ha = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ha)


def require_ok(value):
    if value != "OK":
        raise RuntimeError("Redis did not accept the configuration change")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=["master", "slave"])
    parser.add_argument("operation", choices=["gate", "adopt"])
    args = parser.parse_args()
    redis = f"wao-{args.role}-redis-{args.role}-1"
    sentinel = f"wao-{args.role}-sentinel-{args.role}-1"
    node = "192.168.31.180" if args.role == "master" else "192.168.2.180"

    def s(*command):
        return ha.query(sentinel, "127.0.0.1", 26379, *command)

    def r(*command):
        return ha.query(redis, "127.0.0.1", 6379, *command)

    role = r("ROLE")
    if role[0] != ("master" if args.role == "master" else "slave"):
        raise RuntimeError("Current role differs from the reviewed baseline; no adoption")
    if args.operation == "gate":
        require_ok(s("SENTINEL", "SET", "wao-master", "quorum", "4"))
        require_ok(s("SENTINEL", "FLUSHCONFIG"))
        print(f"{args.role}: quorum=4 persisted; automatic failover gated")
        return
    # Both existing monitors must already be gated before changing endpoints.
    for host in ha.DATA_NODES:
        view = ha.pairs(ha.query(sentinel, host, 26379, "SENTINEL", "MASTER", "wao-master"))
        if int(view["quorum"]) != 4:
            raise RuntimeError("Gate both existing Sentinels before adopting private endpoints")
    require_ok(r("CONFIG", "SET", "replica-announce-ip", node))
    require_ok(r("CONFIG", "SET", "replica-announce-port", "6379"))
    require_ok(r("CONFIG", "SET", "maxmemory", "536870912"))
    require_ok(r("CONFIG", "SET", "maxmemory-policy", "noeviction"))
    if args.role == "slave":
        require_ok(r("REPLICAOF", "192.168.31.180", "6379"))
    require_ok(r("CONFIG", "REWRITE"))
    identity = s("SENTINEL", "MYID")
    require_ok(s("SENTINEL", "CONFIG", "SET", "announce-ip", node))
    require_ok(s("SENTINEL", "CONFIG", "SET", "announce-hostnames", "no"))
    require_ok(s("SENTINEL", "REMOVE", "wao-master"))
    require_ok(s("SENTINEL", "MONITOR", "wao-master", "192.168.31.180", "6379", "4"))
    # Pass the authentication value via stdin inside the container, not argv.
    result = subprocess.run(["docker", "exec", sentinel, "sh", "-ec",
                             'export REDISCLI_AUTH="$REDIS_PASS"; printf %s "$REDIS_PASS" | '
                             'redis-cli --no-auth-warning -p 26379 -x SENTINEL SET wao-master auth-pass'],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12)
    if result.returncode or result.stdout.strip() != b"OK":
        raise RuntimeError("Sentinel authentication setup failed; keep failover gated")
    require_ok(s("SENTINEL", "SET", "wao-master", "down-after-milliseconds", "10000"))
    require_ok(s("SENTINEL", "SET", "wao-master", "failover-timeout", "60000"))
    require_ok(s("SENTINEL", "SET", "wao-master", "parallel-syncs", "1"))
    require_ok(s("SENTINEL", "FLUSHCONFIG"))
    if s("SENTINEL", "MYID") != identity:
        raise RuntimeError("Sentinel identity unexpectedly changed")
    print(f"{args.role}: private endpoints persisted; quorum remains 4, no role promoted")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        print(str(error) if isinstance(error, RuntimeError) else "Private topology adoption failed")
        raise SystemExit(1)
