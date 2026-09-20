#!/usr/bin/env python3
"""Adopt the existing two-Sentinel topology without changing live roles.

Secrets stay in memory/private Docker volumes, never in output or command args.
This is not a bootstrap tool for a lost cluster.
"""
import argparse
import json
import subprocess


def docker(*args, data=None):
    result = subprocess.run(["docker", *args], input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError("Docker command failed; inspect locally without exporting credentials")
    return result.stdout


def cli(container, port, *args):
    raw = docker("exec", container, "sh", "-ec",
                 'export REDISCLI_AUTH="$REDIS_PASS"; exec redis-cli --no-auth-warning --json -p "$@"',
                 "redis-state", str(port), *args)
    return json.loads(raw)


def as_dict(value):
    return dict(zip(value[::2], value[1::2])) if isinstance(value, list) else value


def config_text(base, settings, role):
    lines = [base.rstrip(), "# Adopted live state; use CONFIG REWRITE after changes."]
    for key, value in settings.items():
        lines.append(f"{key} {json.dumps(str(value), ensure_ascii=False)}")
    if role[0] == "slave":
        lines.append(f"replicaof {json.dumps(role[1])} {int(role[2])}")
    elif role[0] != "master":
        raise ValueError("Unexpected Redis role")
    return ("\n".join(lines) + "\n").encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=["master", "slave"])
    parser.add_argument("--prepare", action="store_true", help="write private state; default is read-only")
    args = parser.parse_args()
    prefix = f"wao-{args.role}"
    redis = f"{prefix}-redis-{args.role}-1"
    sentinel = f"{prefix}-sentinel-{args.role}-1"
    volume = f"{prefix}_sentinel-{args.role}-data"
    role = cli(redis, 6379, "ROLE")
    master = as_dict(cli(sentinel, 26379, "SENTINEL", "MASTER", "wao-master"))
    if int(master["quorum"]) < 3 or int(master["num-other-sentinels"]) > 1:
        raise RuntimeError("Only the existing two-Sentinel, quorum>=3 topology can be adopted")
    if any(flag in master["flags"] for flag in ["failover", "s_down", "o_down"]):
        raise RuntimeError("Monitored primary is not healthy")
    if (args.role == "master" and role[0] != "master") or (args.role == "slave" and role[0] != "slave"):
        raise RuntimeError("Roles differ from the reviewed baseline")
    image = json.loads(docker("inspect", redis))[0]["Image"]
    keys = ["requirepass", "masterauth", "replica-announce-ip", "replica-announce-port",
            "min-replicas-to-write", "min-replicas-max-lag", "replica-priority",
            "maxmemory", "maxmemory-policy"]
    settings = as_dict(cli(redis, 6379, "CONFIG", "GET", *keys))
    if set(settings) != set(keys) or not settings["requirepass"] or not settings["masterauth"]:
        raise RuntimeError("Incomplete live authentication/runtime configuration")
    base = docker("exec", redis, "cat", "/usr/local/etc/redis/redis.conf").decode()
    content = config_text(base, settings, role)
    print(f"{args.role}: live role={role[0]}, quorum={master['quorum']}; adoption preflight passed")
    if not args.prepare:
        return
    if cli(sentinel, 26379, "SENTINEL", "FLUSHCONFIG") != "OK":
        raise RuntimeError("Sentinel did not persist current state")
    snapshot = docker("exec", sentinel, "cat", "/tmp/sentinel.conf")
    if b"sentinel myid " not in snapshot or b"sentinel monitor wao-master " not in snapshot:
        raise RuntimeError("Sentinel snapshot lacks identity/monitor")
    # Check both destinations before writing. Existing state is never replaced.
    docker("exec", redis, "sh", "-ec", "test ! -e /data/wao-runtime/redis.conf")
    docker("volume", "create", volume)
    docker("run", "--rm", "--network", "none", "-v", f"{volume}:/state", image,
           "sh", "-ec", "test ! -e /state/sentinel.conf")
    docker("exec", "-i", redis, "sh", "-ec",
           "umask 077; mkdir -p /data/wao-runtime; cat > /data/wao-runtime/redis.conf.tmp; "
           "chown redis:redis /data/wao-runtime /data/wao-runtime/redis.conf.tmp; "
           "chmod 700 /data/wao-runtime; mv /data/wao-runtime/redis.conf.tmp /data/wao-runtime/redis.conf",
           data=content)
    docker("run", "--rm", "-i", "--network", "none", "-v", f"{volume}:/state", image,
           "sh", "-ec", "umask 077; cat > /state/sentinel.conf.tmp; chmod 700 /state; "
           "chown redis:redis /state /state/sentinel.conf.tmp; mv /state/sentinel.conf.tmp /state/sentinel.conf",
           data=snapshot)
    print("Prepared private Redis/Sentinel state; no service restarted, no role/quorum changed")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, KeyError) as error:
        # JSON parsing errors/command output can contain secrets.
        print(f"Preparation refused: {type(error).__name__}; no automatic reset or promotion")
        raise SystemExit(2)
