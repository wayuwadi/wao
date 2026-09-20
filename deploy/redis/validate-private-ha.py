#!/usr/bin/env python3
"""Validate private Sentinel consensus and live roles; never modify topology."""
import argparse
import json
import subprocess

SENTINELS = ("192.168.31.180", "192.168.2.180", "10.66.0.1")
DATA_NODES = SENTINELS[:2]


def pairs(value):
    return dict(zip(value[::2], value[1::2])) if isinstance(value, list) else value


def query(container, host, port, *args):
    result = subprocess.run(["docker", "exec", container, "sh", "-ec",
                             'export REDISCLI_AUTH="$REDIS_PASS"; exec redis-cli --no-auth-warning --json "$@"',
                             "redis-ha", "-h", host, "-p", str(port), *args],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12)
    if result.returncode:
        raise RuntimeError(f"Private Redis endpoint {host}:{port} unavailable")
    try:
        return json.loads(result.stdout)
    except ValueError:
        raise RuntimeError(f"Private Redis endpoint {host}:{port} did not return valid JSON") from None


def check_views(views, quorum):
    primaries = set()
    for address, view in views:
        if view["ip"] not in DATA_NODES or str(view["port"]) != "6379":
            raise RuntimeError("Sentinel advertised an unapproved data endpoint")
        if int(view["quorum"]) != quorum or int(view["num-other-sentinels"]) != 2:
            raise RuntimeError("Sentinel quorum/membership differs from the three-host topology")
        if any(flag in view["flags"] for flag in ("s_down", "o_down", "failover")):
            raise RuntimeError("Sentinel reports an unhealthy primary")
        primaries.add(view["ip"])
    if len(primaries) != 1:
        raise RuntimeError("Sentinels disagree on the primary")
    return next(iter(primaries))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=["master", "slave"])
    parser.add_argument("--quorum", type=int, choices=[2, 4], default=2)
    args = parser.parse_args()
    container = f"wao-{args.role}-sentinel-{args.role}-1"
    views = [(host, pairs(query(container, host, 26379, "SENTINEL", "MASTER", "wao-master")))
             for host in SENTINELS]
    primary = check_views(views, args.quorum)
    identities = set()
    for host in SENTINELS:
        identities.add(query(container, host, 26379, "SENTINEL", "MYID"))
        peers = query(container, host, 26379, "SENTINEL", "SENTINELS", "wao-master")
        for entry in peers:
            peer = pairs(entry)
            if peer["ip"] not in SENTINELS or "disconnected" in peer["flags"] or "s_down" in peer["flags"]:
                raise RuntimeError("Sentinel peer is unavailable or still uses a public endpoint")
        if args.quorum == 2:
            result = query(container, host, 26379, "SENTINEL", "CKQUORUM", "wao-master")
            if not isinstance(result, str) or not result.startswith("OK"):
                raise RuntimeError("Sentinel majority is not available")
    if len(identities) != 3:
        raise RuntimeError("Sentinel identities must be independent")
    for host in DATA_NODES:
        role = query(container, host, 6379, "ROLE")
        if host == primary:
            if role[0] != "master" or len(role[2]) != 1:
                raise RuntimeError("Primary does not have exactly one replica")
        elif role[0] != "slave" or role[1] != primary or role[3] != "connected":
            raise RuntimeError("Replica does not follow the elected private primary")
    print(f"Private Redis topology valid: primary={primary}, three independent Sentinels, quorum={args.quorum}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        # Do not print Redis error bodies or credentials.
        print(str(error) if isinstance(error, RuntimeError) else "Private Redis topology validation failed")
        raise SystemExit(1)
