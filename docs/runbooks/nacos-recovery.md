# 嵌入式 Nacos 的备份与隔离恢复

## 边界

当前实机 2.3.2 standalone、内置 Derby、无挂载卷；先保护完整现状，不在此升级到目标 2.4.3。
只有备份进程短暂停止 Nacos，WAO Gateway 仍按 ADR-006 路由同机应用。
这不提供自动接管；切换真实客户端前仍需确认旧实例已隔离、唯一活动端点及重新注册。

## 安装

Linux2 root 安装到 `/usr/local/lib/wao-backup/`：
`create-nacos-recovery.sh`、`nacos-recovery-inventory.py`、`peer-retention.py`，权限 0700。
复用 root 0600 的 `/etc/wao-backup/peer.env` 与现有加密口令和 SSH 信任，不新增明文凭据。

将 `wao-infra-maintenance.conf` 放到 `/etc/tmpfiles.d/`，执行
`systemd-tmpfiles --create /etc/tmpfiles.d/wao-infra-maintenance.conf`。
核验 root:jenkins 0660 的锁文件，确认 Jenkins 使用含共享锁的新 `ci-deploy.sh`。
将 `wao-nacos-backup.service`、`.timer` 放到 `/etc/systemd/system/`，daemon-reload 后启用 timer。
备份服务不要与旧版部署脚本同时启动。人工重启 WAO 应用也应先持有该共享锁。

Linux1 用户安装脚本到 `~/.local/libexec/wao-backup/`：
`verify-nacos-recovery.sh`、`nacos-recovery-inventory.py`、更新后的 `restore-latest-peer-backup.sh`、
`check-peer-backup.sh`、`check-peer-backup.py`。
将 `wao-nacos-backup-check.*`、`wao-nacos-restore.*` 安装到 `~/.config/systemd/user/`，
执行 `systemctl --user daemon-reload` 并启用两个 timer。沿用现有用户 linger。
独立告警复用原有私有 webhook；不会把 URL 或口令写进输出。

## 手工验证与恢复

```bash
# Linux2：备份会短暂停止并重新启动现有 Nacos，须在维护窗口执行。
sudo /usr/local/lib/wao-backup/create-nacos-recovery.sh

# Linux1：只启动不联网的临时恢复容器，不覆盖当前生产服务。
~/.local/libexec/wao-backup/verify-nacos-recovery.sh \
  ~/backups/wao-peer-nacos/<UTC快照目录> ~/.config/wao-backup/peer-passphrase
WAO_PEER_SNAPSHOT_ROOT="$HOME/backups/wao-peer-nacos" \
  ~/.local/libexec/wao-backup/check-peer-backup.sh --check-only --label Nacos
```

Linux2 `/var/backups/wao-peer-nacos/<UTC>`、Linux1 `~/backups/wao-peer-nacos/<UTC>` 只保存密文、
SHA256SUMS 和 COMPLETE。没有 COMPLETE 的传输不算恢复点。
全量镜像包含配置、权限、持久协议数据与启动环境，解密后不得公开或上传代码仓库。
日常进程退出会清理临时容器、解密目录和恢复镜像。主机强制断电后应检查残留，不能盲目删除生产数据。

## 2026-09-20 验收

- 快照 `20260920T095633Z`，密文 398265896 字节，两端 SHA256 验证通过。
- 原容器干净停止后取像并重新就绪；重启前后命名空间/配置正文摘要一致。
- Linux1 隔离恢复 v4 退出 0，用已有账号认证并核对 1 个命名空间、1 项配置正文摘要一致。
- 摘要 `819341034e1ee138407d5e3a1ce82f1599139d7f8b894d4f144c686dc8580e1e`。
- 原始验收失败分别发现 Docker 弃用警告污染 ID，以及 Java 8 默认线程数超过 256 PID；
  已改为独立读取镜像 ID，恢复时 JVM 可见 CPU=2、PID=512，保留 1 CPU/1.5GiB 上限。
- 两个失败注入用例覆盖非干净停止及镜像提交失败，均自动重启原 Nacos、拒绝发布恢复点。
- 临时恢复容器、恢复镜像和解密暂存目录清理完成。
- 未进行实际客户端接管、Nacos 升级、自动高可用、权限矩阵穷举或真实告警发送验收。

首次人工验收不等于定时服务已启用；运行状态以 systemd 和独立新鲜度检查为准。
