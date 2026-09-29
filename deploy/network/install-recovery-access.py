#!/usr/bin/env python3
"""Install a forwarding-only cloud account using an existing operator PUBLIC key."""
import argparse
import base64
import datetime
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess

USER = "wao-recovery"
MANAGED = Path("/etc/wao-recovery-access")
INCLUDE = "Include /etc/ssh/wao-recovery.conf"


def normalized_key(text):
    parts = text.strip().split()
    if len(parts) < 2 or parts[0] != "ssh-ed25519" or not re.fullmatch(r"[A-Za-z0-9+/=]+", parts[1]):
        raise ValueError("Expected one Ed25519 public key, never a private key")
    decoded = base64.b64decode(parts[1], validate=True)
    if len(decoded) != 51 or decoded[4:15] != b"ssh-ed25519":
        raise ValueError("Invalid Ed25519 public key")
    return " ".join(parts[:2])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("public_key", type=Path)
    parser.add_argument("--template", type=Path, default=Path(__file__).with_name("wao-recovery-sshd.conf"))
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise RuntimeError("Run on the cloud host as its authorized administrator")
    key = normalized_key(args.public_key.read_text())
    try:
        account = pwd.getpwnam(USER)
        if not MANAGED.exists():
            raise RuntimeError("Existing unmanaged account; refuse to adopt it")
    except KeyError:
        subprocess.run(["useradd", "--system", "--create-home", "--shell", "/usr/sbin/nologin", USER], check=True)
        account = pwd.getpwnam(USER)
    MANAGED.mkdir(mode=0o700, exist_ok=True)
    sshdir = Path(account.pw_dir) / ".ssh"
    sshdir.mkdir(mode=0o700, exist_ok=True)
    os.chmod(sshdir, 0o700)
    os.chown(sshdir, account.pw_uid, account.pw_gid)
    authorized = sshdir / "authorized_keys"
    previous = authorized.read_text() if authorized.exists() else ""
    row = 'restrict,port-forwarding,permitopen="10.66.0.2:22" ' + key
    retired_row = 'restrict,port-forwarding,permitopen="10.66.0.3:22",permitopen="10.66.0.2:22" ' + key
    rows = [row if existing == retired_row else existing for existing in previous.splitlines()]
    if row not in rows:
        rows.append(row)
    if rows != previous.splitlines():
        authorized.write_text("\n".join(rows) + "\n")
    os.chmod(authorized, 0o600)
    os.chown(authorized, account.pw_uid, account.pw_gid)
    main_config = Path("/etc/ssh/sshd_config")
    original = main_config.read_text()
    snippet = Path("/etc/ssh/wao-recovery.conf")
    if snippet.exists() and snippet.read_text() != args.template.read_text():
        raise RuntimeError("Existing recovery policy differs; review before replacing it")
    snippet.write_text(args.template.read_text())
    os.chmod(snippet, 0o644)
    candidate = MANAGED / "sshd_config.candidate"
    candidate.write_text(original if INCLUDE in original.splitlines() else
                         original.rstrip() + "\n\n# WAO restricted recovery jump\nMatch all\n" + INCLUDE + "\n")
    os.chmod(candidate, 0o600)
    subprocess.run(["/usr/sbin/sshd", "-t", "-f", str(candidate)], check=True)
    effective = subprocess.check_output(["/usr/sbin/sshd", "-T", "-f", str(candidate),
                                         "-C", f"user={USER},host=recovery-client,addr=127.0.0.1"], text=True)
    for required in ["passwordauthentication no", "authenticationmethods publickey", "maxsessions 0",
                     "allowtcpforwarding local", "permitopen 10.66.0.2:22"]:
        if required not in effective.splitlines():
            raise RuntimeError("Effective SSH policy did not match the restricted design")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup = MANAGED / ("sshd_config.before-" + stamp)
    shutil.copyfile(main_config, backup)
    os.chmod(backup, 0o600)
    os.chmod(candidate, main_config.stat().st_mode & 0o777)
    os.replace(candidate, main_config)
    try:
        subprocess.run(["systemctl", "reload", "ssh"], check=True)
    except subprocess.CalledProcessError:
        shutil.copyfile(backup, main_config)
        subprocess.run(["/usr/sbin/sshd", "-t"], check=True)
        subprocess.run(["systemctl", "reload", "ssh"], check=True)
        raise RuntimeError("Reload failed; restored the previous SSH configuration") from None
    print("Restricted recovery jump installed; public key only, no general cloud shell access")


if __name__ == "__main__":
    main()
