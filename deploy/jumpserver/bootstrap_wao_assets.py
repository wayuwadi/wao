"""Idempotently register WAO hosts, protocols, accounts, and member grants."""

import os

from accounts.models import Account
from assets.models import Host, Node, Platform, Protocol
from common.utils import asset_permission_date_expired_default
from django.utils import timezone
from orgs.models import Organization
from orgs.utils import tmp_to_org
from perms.models import ActionChoices, AssetPermission
from users.models import User, UserGroup


AUTHORIZED_MEMBER_NAMES = {"娃哥", "娃弟"}
AUTHORIZED_GROUP_NAME = "WAO 基础设施维护者"
SSH_PERMISSION_NAME = "WAO 双人 SSH 管理"
RDP_PERMISSION_NAME = "WAO 双人 Windows 图形管理"


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
    registered_assets = {}
    ssh_usernames = set()
    for name, address, username, platform_type in assets:
        platform = Platform.objects.filter(category="host", type=platform_type).first()
        if platform is None and platform_type == "windows":
            platform = Platform.objects.filter(category="host", name__icontains="Windows").first()
        if platform is None and platform_type == "linux":
            platform = Platform.objects.filter(category="host", type="linux").first()
        if platform is None:
            raise RuntimeError("JumpServer host platform is unavailable: " + platform_type)
        if platform_type == "windows" and not platform.protocols.filter(name="rdp").exists():
            raise RuntimeError("JumpServer Windows platform does not support RDP")

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
        if platform_type == "windows":
            Protocol.objects.update_or_create(
                asset=asset, name="rdp", defaults={"port": 3389}
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
        registered_assets[name] = asset
        ssh_usernames.add(username)

    if Host.objects.filter(name__startswith="WAO ").count() < 3:
        raise RuntimeError("WAO assets were not fully registered")

    authorized_users = list(User.objects.filter(
        username__startswith="wao_",
        name__in=AUTHORIZED_MEMBER_NAMES,
        is_active=True,
    ))
    found_names = {user.name for user in authorized_users}
    missing_names = AUTHORIZED_MEMBER_NAMES - found_names
    if missing_names or len(authorized_users) != len(AUTHORIZED_MEMBER_NAMES):
        raise RuntimeError(
            "WAO JumpServer authorized users are missing or ambiguous: "
            + ", ".join(sorted(missing_names or found_names))
        )

    group, _ = UserGroup.objects.update_or_create(
        name=AUTHORIZED_GROUP_NAME,
        defaults={"comment": "仅包含娃哥、娃弟，用于 WAO 基础设施资产授权。"},
    )
    group.users.set(authorized_users)

    permission_defaults = {
        "actions": ActionChoices.all(),
        "date_start": timezone.now() - timezone.timedelta(seconds=1),
        "date_expired": asset_permission_date_expired_default(),
        "is_active": True,
    }
    ssh_permission, _ = AssetPermission.objects.update_or_create(
        name=SSH_PERMISSION_NAME,
        defaults={
            **permission_defaults,
            "accounts": sorted(ssh_usernames),
            "protocols": ["ssh"],
            "comment": "娃哥、娃弟使用 JumpServer 专用 SSH 密钥管理三台 WAO 主机。",
        },
    )
    # JumpServer v4.10.19 的权限缓存信号不能处理 clear() 发送的 pk_set=None；
    # set([]) 只对真实差集发送带主键的 remove 事件，并保持幂等收敛。
    ssh_permission.users.set([])
    ssh_permission.user_groups.set([group])
    ssh_permission.assets.set(list(registered_assets.values()))
    ssh_permission.nodes.set([])

    rdp_permission, _ = AssetPermission.objects.update_or_create(
        name=RDP_PERMISSION_NAME,
        defaults={
            **permission_defaults,
            "accounts": ["@INPUT"],
            "protocols": ["rdp"],
            "comment": "娃哥、娃弟连接时手动输入真实 Windows 凭据，不在 JumpServer 中复制密码。",
        },
    )
    rdp_permission.users.set([])
    rdp_permission.user_groups.set([group])
    rdp_permission.assets.set([registered_assets["WAO Windows 从节点"]])
    rdp_permission.nodes.set([])

print("WAO JumpServer assets and dual-member SSH/RDP grants registered")
