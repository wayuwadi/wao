"""Idempotently register WAO's three real hosts and their dedicated SSH account."""

import os

from accounts.models import Account
from assets.models import Host, Node, Platform, Protocol
from orgs.models import Organization
from orgs.utils import tmp_to_org


def required(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError("Missing asset setting: " + name)
    return value


private_key_path = "/opt/jumpserver/data/bootstrap/wao-assets"
with open(private_key_path, encoding="utf-8") as key_file:
    private_key = key_file.read().strip()
if not private_key.startswith("-----BEGIN OPENSSH PRIVATE KEY-----"):
    raise RuntimeError("JumpServer asset private key is invalid")

assets = [
    ("WAO Linux 主节点", required("WAO_JMS_MASTER_ADDRESS"), required("WAO_JMS_MASTER_USER"), "linux"),
    ("WAO Windows 从节点", required("WAO_JMS_WINDOWS_ADDRESS"), required("WAO_JMS_WINDOWS_USER"), "windows"),
    ("WAO 阿里云入口", required("WAO_JMS_CLOUD_ADDRESS"), required("WAO_JMS_CLOUD_USER"), "linux"),
]

with tmp_to_org(Organization.default()):
    node, _ = Node.org_root().get_or_create_child("WAO 基础设施")
    for name, address, username, platform_type in assets:
        platform = Platform.objects.filter(category="host", type=platform_type).first()
        if platform is None and platform_type == "windows":
            platform = Platform.objects.filter(category="host", name__icontains="Windows").first()
        if platform is None:
            platform = Platform.objects.filter(category="host", type="linux").first()
        if platform is None:
            raise RuntimeError("JumpServer host platform is unavailable: " + platform_type)

        asset, _ = Host.objects.update_or_create(
            name=name,
            defaults={
                "address": address,
                "platform": platform,
                "is_active": True,
                "comment": "由 WAO 主发布链路纳管；账号使用独立 SSH 密钥。",
            },
        )
        asset.nodes.set([node])
        Protocol.objects.update_or_create(
            asset=asset, name="ssh", defaults={"port": 22}
        )
        Account.objects.update_or_create(
            asset=asset,
            name="wao-jumpserver",
            defaults={
                "username": username,
                "secret_type": "ssh_key",
                "secret": private_key,
                "privileged": True,
                "is_active": True,
                "comment": "WAO 自动生成的 JumpServer 专用维护账户",
            },
        )

    if Host.objects.filter(name__startswith="WAO ").count() < 3:
        raise RuntimeError("WAO assets were not fully registered")

print("WAO JumpServer assets registered: 3")
