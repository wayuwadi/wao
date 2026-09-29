"""Fail-closed production verification for WAO JumpServer asset access."""

from accounts.models import Account
from assets.models import Gateway, Host, Zone
from orgs.models import Organization
from orgs.utils import tmp_to_org
from perms.models import ActionChoices, AssetPermission
from users.models import User, UserGroup


ASSET_NAMES = {
    "WAO Linux 主节点",
    "WAO 阿里云入口",
}
AUTHORIZED_MEMBER_NAMES = {"娃哥", "娃弟"}
AUTHORIZED_GROUP_NAME = "WAO 基础设施维护者"
SSH_PERMISSION_NAME = "WAO 双人 SSH 管理"
RETIRED_WINDOWS_ASSET_NAME = "WAO Windows 从节点"
RETIRED_RDP_PERMISSION_NAME = "WAO 双人 Windows 图形管理"
RETIRED_LINUX_ASSET_NAME = "WAO Linux 从节点"
RETIRED_GATEWAY_NAME = "WAO Linux2 隧道网关"
RETIRED_ZONE_NAME = "WAO Linux1 反向隧道"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


with tmp_to_org(Organization.default()):
    assets = {asset.name: asset for asset in Host.objects.filter(name__in=ASSET_NAMES)}
    require(set(assets) == ASSET_NAMES, "WAO JumpServer asset inventory is incomplete")

    for asset in assets.values():
        accounts = Account.objects.filter(asset=asset, name="wao-jumpserver", is_active=True)
        require(accounts.count() == 1, "Dedicated account is missing for " + asset.name)
        ssh = asset.protocols.filter(name="ssh", port=22)
        require(ssh.count() == 1, "SSH protocol is invalid for " + asset.name)

    require(not Host.objects.filter(name=RETIRED_LINUX_ASSET_NAME, is_active=True).exists(),
            "Retired Linux asset is still active")
    require(not Account.objects.filter(asset__name=RETIRED_LINUX_ASSET_NAME, is_active=True).exists(),
            "Retired Linux account is still active")
    require(not Gateway.objects.filter(name=RETIRED_GATEWAY_NAME, is_active=True).exists(),
            "Retired tunnel gateway is still active")
    require(not Zone.objects.filter(name=RETIRED_ZONE_NAME, gateways__is_active=True).exists(),
            "Retired tunnel zone still has an active gateway")

    users = list(User.objects.filter(
        username__startswith="wao_",
        name__in=AUTHORIZED_MEMBER_NAMES,
        is_active=True,
    ))
    require({user.name for user in users} == AUTHORIZED_MEMBER_NAMES, "WAO users are incomplete")
    require(len(users) == len(AUTHORIZED_MEMBER_NAMES), "WAO users are ambiguous")

    group = UserGroup.objects.get(name=AUTHORIZED_GROUP_NAME)
    require(
        set(group.users.filter(is_active=True).values_list("name", flat=True))
        == AUTHORIZED_MEMBER_NAMES,
        "WAO infrastructure group membership is invalid",
    )
    require(group.users.filter(is_active=True).count() == 2, "WAO infrastructure group is too broad")

    ssh_permission = AssetPermission.objects.get(name=SSH_PERMISSION_NAME)
    require(ssh_permission.is_valid, "WAO SSH permission is not valid")
    require(ssh_permission.actions == ActionChoices.all(), "WAO SSH actions are incomplete")
    require(ssh_permission.protocols == ["ssh"], "WAO SSH protocol scope is invalid")
    require(
        set(ssh_permission.accounts)
        == set(Account.objects.filter(
            asset__name__in=ASSET_NAMES,
            name="wao-jumpserver",
        ).values_list("username", flat=True)),
        "WAO SSH account scope is invalid",
    )
    require(
        set(ssh_permission.assets.values_list("name", flat=True)) == ASSET_NAMES,
        "WAO SSH asset scope is invalid",
    )
    require(
        list(ssh_permission.user_groups.values_list("name", flat=True))
        == [AUTHORIZED_GROUP_NAME],
        "WAO SSH user group scope is invalid",
    )
    require(not ssh_permission.users.exists(), "WAO SSH permission has unmanaged direct users")

    retired_assets = Host.objects.filter(name=RETIRED_WINDOWS_ASSET_NAME)
    require(
        not retired_assets.filter(is_active=True).exists(),
        "Retired Windows asset is still active",
    )
    retired_permissions = AssetPermission.objects.filter(name=RETIRED_RDP_PERMISSION_NAME)
    require(
        not retired_permissions.filter(is_active=True).exists(),
        "Retired Windows RDP permission is still active",
    )

print("WAO JumpServer Linux2/cloud asset access verification passed")
