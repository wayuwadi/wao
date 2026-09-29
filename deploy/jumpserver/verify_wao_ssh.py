"""Connect from JumpServer Core using the persisted asset/account/gateway route."""

from contextlib import ExitStack
from io import StringIO

import paramiko
from assets.models import Host
from orgs.models import Organization
from orgs.utils import tmp_to_org


def connect_asset(asset, stack, sock=None):
    account = asset.accounts.get(name="wao-jumpserver", is_active=True)
    if account.secret_type != "ssh_key":
        raise RuntimeError("Dedicated SSH key account is missing for " + asset.name)
    key = paramiko.Ed25519Key.from_private_key(StringIO(account.secret))
    client = stack.enter_context(paramiko.SSHClient())
    # Only trust host keys already checked by the host-side dedicated-key probes.
    client.load_host_keys("/opt/jumpserver/data/bootstrap/asset-known-hosts")
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    client.connect(asset.address, port=asset.get_protocol_port("ssh"),
                   username=account.username, pkey=key, sock=sock,
                   timeout=12, auth_timeout=12, banner_timeout=12,
                   allow_agent=False, look_for_keys=False)
    return client


def verify_asset(asset):
    with ExitStack() as stack:
        channel = None
        if asset.gateway is not None:
            gateway = connect_asset(asset.gateway, stack)
            channel = stack.enter_context(gateway.get_transport().open_channel(
                "direct-tcpip", (asset.address, asset.get_protocol_port("ssh")),
                ("127.0.0.1", 0), timeout=12,
            ))
        client = connect_asset(asset, stack, channel)
        _, stdout, _ = client.exec_command("exit", timeout=12)
        if stdout.channel.recv_exit_status() != 0:
            raise RuntimeError("SSH command failed for " + asset.name)
    print(asset.name + " JumpServer persisted SSH route verified.")


with tmp_to_org(Organization.default()):
    for name in ("WAO Linux 主节点", "WAO 阿里云入口"):
        verify_asset(Host.objects.get(name=name, is_active=True))
