# 独立维护入口

Linux1 已退役。当前仓库授权脚本只允许通过云端跳板转发到 Linux2；下文中的 Linux1 别名不再是有效目标，也不应重新配置。

## 当前电脑使用

```powershell
ssh linux2-recovery
```

这两个本机别名通过 `wao-cloud-recovery` 跳板，连接目标私网 SSH。
配置位于用户 `.ssh/wao-recovery-config`，由既有 `.ssh/config` 顶部 Include 引入。
云机专用主机公钥文件为 `.ssh/wao-recovery-known-hosts`；目标 Linux 使用已有主机身份。
只使用 `linux2-recovery` 维护当前 Linux2。不要将已退役的 Linux1 别名改指到其他主机。

## 安装到云机

管理员通过既有可信管理通道传递下列两个仓库文件与操作人的公钥：

- `deploy/network/install-recovery-access.py`
- `deploy/network/wao-recovery-sshd.conf`
- 操作人已有的 `.pub` 文件，禁止传递私钥。

在云机以管理员身份运行：

```sh
python3 install-recovery-access.py operator.pub
```

检查新连接成功后再结束原管理会话。新增操作人需明确授权，不能自动收集或安装所有人的公钥。
不要使用关闭 StrictHostKeyChecking 或未经验证的 ssh-keyscan 代替可信主机公钥分发。

## 验证与撤销

正常路径运行 `hostname`、`id -un` 并核对目标机 SSH_CONNECTION 的来源为云机私网地址。
`ssh wao-cloud-recovery true` 和 `ssh -W 10.66.0.2:6379 wao-cloud-recovery` 应失败。
这证明限制策略生效，不代表整机故障演练完成。

撤销单个操作人：管理员从云机 `/home/wao-recovery/.ssh/authorized_keys` 删除对应公钥行，随后验证新连接被拒绝。
撤销整个入口：先保留可信管理员会话，清空专用账号授权，移除主配置末尾的 WAO Include，执行 `sshd -t` 成功后 reload SSH。
不要用过期整份备份覆盖后来变更的 SSH 配置；原配置备份保存在 root 专用 `/etc/wao-recovery-access`。
本机移除该 Include 和当前恢复别名即可停用本地入口。
