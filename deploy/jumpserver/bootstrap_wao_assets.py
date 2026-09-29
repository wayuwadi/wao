"""Idempotently register WAO hosts, protocols, accounts, and member grants."""

import os

from accounts.models import Account
from assets.models import Gateway, Host, Node, Platform, Protocol, Zone
from common.utils import asset_permission_date_expired_default
from django.utils import timezone
from orgs.models import Organization
from orgs.utils import tmp_to_org
from perms.models import ActionChoices, AssetPermission
from users.models import User, UserGroup


AUTHORIZED_MEMBER_NAMES = {"娃哥", "娃弟"}
AUTHORIZED_GROUP_NAME = "WAO 基础设施维护者"
SSH_PERMISSION_NAME = "WAO 双人 SSH 管理"
RETIRED_LINUX_ASSET_NAME = "WAO Linux 从节点"
RETIRED_WINDOWS_ASSET_NAME = "WAO Windows 从节点"
RETIRED_RDP_PERMISSION_NAME = "WAO 双人 Windows 图形管理"
RETIRED_GATEWAY_NAME = "WAO Linux2 隧道网关"
RETIRED_ZONE_NAME = "WAO Linux1 反向隧道"


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
    ("WAO Linux 主节点", required("WAO_JMS_MASTER_ADDRESS"), required("WAO_JMS_MASTER_USER")),
    ("WAO 阿里云入口", required("WAO_JMS_CLOUD_ADDRESS"), required("WAO_JMS_CLOUD_USER")),
]

with tmp_to_org(Organization.default()):
    node, _ = Node.org_root().get_or_create_child("WAO 基础设施")
    platform = Platform.objects.filter(name="Linux", category="host", type="linux").first()
    if platform is None:
        raise RuntimeError("JumpServer Linux host platform is unavailable")
    # The former Linux1 SSH route is retained only as inactive audit history.
    for gateway in Gateway.objects.filter(name=RETIRED_GATEWAY_NAME):
        gateway.is_active = False
        gateway.save(update_fields=["is_active"])
        Account.objects.filter(asset=gateway).update(is_active=False)
    for zone in Zone.objects.filter(name=RETIRED_ZONE_NAME):
        for gateway in zone.gateways.all():
            gateway.is_active = False
            gateway.save(update_fields=["is_active"])
            Account.objects.filter(asset=gateway).update(is_active=False)
    registered_assets = {}
    ssh_usernames = set()
    for name, address, username in assets:
        asset, _ = Host.objects.update_or_create(
            name=name,
            defaults={
                "address": address,
                "platform": platform,
                "is_active": True,
                "zone": None,
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
        registered_assets[name] = asset
        ssh_usernames.add(username)

    if len(registered_assets) != 2:
        raise RuntimeError("WAO assets were not fully registered")

    retired_linux_asset = Host.objects.filter(name=RETIRED_LINUX_ASSET_NAME).first()
    if retired_linux_asset is not None:
        retired_linux_asset.is_active = False
        retired_linux_asset.zone = None
        retired_linux_asset.comment = "历史 Linux1 资产已退役；不再纳入 WAO 运维授权。"
        retired_linux_asset.save(update_fields=["is_active", "zone", "comment"])
        retired_linux_asset.nodes.set([])
        Account.objects.filter(asset=retired_linux_asset).update(is_active=False)
        for permission in AssetPermission.objects.filter(assets=retired_linux_asset).distinct():
            permission.assets.remove(retired_linux_asset)
            if not permission.assets.exists():
                permission.is_active = False
                permission.save(update_fields=["is_active"])

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
            "comment": "娃哥、娃弟使用 JumpServer 专用 SSH 密钥管理 Linux2 与云入口。",
        },
    )
    # JumpServer v4.10.19 的权限缓存信号不能处理 clear() 发送的 pk_set=None；
    # set([]) 只对真实差集发送带主键的 remove 事件，并保持幂等收敛。
    ssh_permission.users.set([])
    ssh_permission.user_groups.set([group])
    ssh_permission.assets.set(list(registered_assets.values()))
    ssh_permission.nodes.set([])

    retired_asset = Host.objects.filter(name=RETIRED_WINDOWS_ASSET_NAME).first()
    if retired_asset is not None:
        retired_asset.is_active = False
        retired_asset.comment = "历史 Windows 节点资产已退役。"
        retired_asset.save(update_fields=["is_active", "comment"])
        retired_asset.nodes.set([])
        Account.objects.filter(asset=retired_asset).update(is_active=False)

    retired_rdp_permission = AssetPermission.objects.filter(
        name=RETIRED_RDP_PERMISSION_NAME
    ).first()
    if retired_rdp_permission is not None:
        retired_rdp_permission.is_active = False
        retired_rdp_permission.comment = "历史 Windows 图形管理权限已退役。"
        retired_rdp_permission.save(update_fields=["is_active", "comment"])
        retired_rdp_permission.users.set([])
        retired_rdp_permission.user_groups.set([])
        retired_rdp_permission.assets.set([])
        retired_rdp_permission.nodes.set([])

print("WAO JumpServer Linux2/cloud assets and dual-member SSH grants registered")
