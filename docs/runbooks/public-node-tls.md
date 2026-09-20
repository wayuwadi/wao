# Linux 公网 HTTPS 基础入口

本单元只解决可信证书、端口可达和证书更新，不改变 WAO 用户业务路由。当前入口
`https://linux1.wayuwa.cn:9443/_edge/health` 返回 `businessRoutingEnabled=false`，其他路径返回 404。
不能把探针通过说成已绕过云机承载 WAO 全部业务；静态版本发布、认证、跨域和降级必须另行验收。

## 当前实机配置

- Linux1 家庭路由新增 TCP 9443 → 192.168.2.180:9443，按网卡 MAC 核对设备；保留原有映射与防火墙。
- 实测宿主机监听公网可达，而 Docker 端口转发路径不通。公共 Nginx 使用 host 网络并仅监听 9443；
  不修改现有防火墙规则。容器限制 0.5 CPU、128 MiB 内存与 64 个进程，只读文件系统和证书挂载。
- 已验证镜像内容标识：`sha256:ef8676b33d681f272ba429b27658bdd7e640963279714c96bddf1dc76307f7b6`。
- Linux1 文件路径：`~/deploy/wao-public-edge/compose.yml`、`nginx/public-node.conf.template`；
  私有证书位于 `~/.config/wao-public-tls/releases/<version>/`，`current` 原子切换。
- Linux2 使用发行版 Certbot，证书目录 `/etc/letsencrypt/live/wao-linux1-public`，初次签发有效期至 2026-12-19。

## 签发、续期与恢复

`node-dns-acme-hook.py` 安装到 Linux1 `~/.local/libexec/`，只允许 linux1/linux2 两个已确定的节点域名。
复用该节点现有 AliDNS 私有凭据文件，凭据不复制到仓库或输出到日志。添加独立 TXT 验证记录，清理前
核对记录 ID、类型、名称和值，拒绝删除被其他人修改的记录。

Linux2 私有 `/etc/wao-node-tls/` 中保存已有部署 SSH 身份的 0600 副本和核验过的 known_hosts，
以及 `node-dns-acme-remote.sh`。Certbot DNS hooks 经 SSH 调用 Linux1 的 DNS 操作，DNS 密钥留在 Linux1。
如节点失联，证书续期会失败，需要恢复节点连接后重跑；这不意味着现有证书立即失效。

`deploy-node-certificate.sh` 安装到 `/etc/letsencrypt/renewal-hooks/deploy/wao-node-certificate.sh`。
它核对域名、有效期和证书/私钥匹配，在 Linux1 原子切换目录；Nginx 配置验证和热加载失败时恢复旧目录。
随后以可信 HTTPS 本地验证入口。旧证书目录保留，不依赖覆盖唯一私钥文件恢复。

2026-09-20 的模拟续期发现 SSH 建连偶发重置；签发成功不等于部署 hook 成功。分发各阶段现有三次有界
重试，并在 Linux2 增加 `wao-public-certificate-sync.{service,timer}`：每日 06:00 独立补发当前证书，
失败每 15 分钟重试（3 小时内最多三次）。这样即使 Certbot 已将新证书标记续期成功，也不会永久漏掉分发。
首次独立分发服务已实机通过；每日任务与 Certbot 自带续期 timer 均需启用。
增加重试后的完整模拟续期再次通过，部署 hook 成功，证书配对、Nginx 校验、热加载和本地可信 HTTPS 均通过。

```bash
sudo certbot renew --cert-name wao-linux1-public --dry-run --run-deploy-hooks
systemctl list-timers certbot.timer
```

Certbot 手动 DNS 验证必须配合认证 hook 才能自动续期，见
[Certbot 官方说明](https://eff-certbot.readthedocs.io/en/stable/using.html#manual)。

## 验收边界与回退

2026-09-20：云节点与 Windows 本机分别通过真实公网 IPv4 访问，并使用系统 CA 验证证书，未使用 `-k`。
证书分发与热更新后本地可信 HTTPS 复验通过。Edge 浏览器访问返回 `ERR_BLOCKED_BY_CLIENT`，没有绕过拦截，
因此浏览器直连、多运营商和 IPv6 体验仍未验收。不能因此打开用户全量直连开关。

随后用户在其常用浏览器反馈收到同一健康 JSON，确认至少该用户网络可直连。此项为用户反馈，
不替代完整 WAO 页面、跨域会话、多运营商或 IPv6 的后续业务验收。

停用独立入口可在其目录执行 `docker compose down`；不停止 WAO 网关、数据库或云入口。
路由器新增的单条映射可单独停用；切勿删除其他 NAT 规则或关闭防火墙。证书与备份私有材料不应进入 Git。

## Linux2 公共入口

Linux2 使用相同受限 Compose，目录 /opt/wao-public-edge；主机网络只监听9443，限额0.5 CPU/128MiB。
证书 /etc/letsencrypt/live/wao-linux2-public 通过既有 DNS hook 签发，私有副本位于
/etc/wao-public-tls-linux2。当前证书到期2026-12-19。

公共静态副本只读挂载 /var/lib/jenkins/wao-static/releases；API仍404，健康状态的业务开关保持false。
UFW仅增加TCP9443规则。启用监听后，Windows和云机使用显式公网IPv4和系统CA均通过，无需修改既有路由器规则。
路由管理登录未成功，随后确认公网已可达；临时管理转发和页面已关闭。未关闭防火墙，未修改其他NAT规则。

Certbot统一部署hook按准确lineage分派，Linux1仍走原远程分发，Linux2调用
/etc/wao-node-tls/deploy-local-node-certificate.sh：校验域名、有效期和公私钥配对后原子换链，
Nginx校验、热加载、本地可信HTTPS失败则恢复旧链接。
独立wao-public-certificate-sync-linux2.timer每天06:10补发，与Linux1任务分离。

```bash
sudo certbot renew --cert-name wao-linux2-public --dry-run --run-deploy-hooks
sudo systemctl status wao-public-certificate-sync-linux2.service
```

本地补发服务已成功；浏览器双节点静态选择另行记录。IPv6尚未独立确认，代理返回的ULA不能算公网IPv6证据。

2026-09-20：Linux2完整模拟续期（含部署hook）通过，域名/有效期/配对、Nginx语法、热加载与可信HTTPS均成功。主站独立验收页已从Linux2直连取得同版静态文件，哈希一致。
