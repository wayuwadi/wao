# WAO 部署说明

当前采用公网 + DDNS 三节点直连，不依赖 Tailscale。原版方案保持归档，实际网络决策以 [ADR-005](../docs/adr/005-public-ddns-network.md) 为准。

## 节点与目录

| 节点                   | 公网地址             | 目录                                 |
| ---------------------- | -------------------- | ------------------------------------ |
| Linux 主节点（娃弟）   | `linux2.wayuwa.cn`   | `$(xdg-user-dir DESKTOP)/deploy/wao` |
| Linux1 从节点（娃哥）  | `linux1.wayuwa.cn`   | `~/deploy/wao`                       |
| 云节点                 | `8.136.152.131`      | `/opt/wao`；前端 `/var/www/wao/dist` |

每台机器的 `deploy/.env` 都是私有文件，不提交 Git。可用保留的环境生成脚本在受信任的管理机生成一份共享的强随机配置：

```powershell
.\deploy\scripts\new-deployment-env.ps1
```

通过已经校验主机密钥的 SSH/SCP 把同一份 `deploy/.env` 复制到主、从两个家庭节点，Linux 上执行 `chmod 600 deploy/.env`。生成脚本不向终端打印密钥。云节点只托管前端和 Nginx，不保存数据库或缓存凭据。

## 公网与路由器前置

先确认两个家庭出口不是运营商 CGNAT，并将域名分别解析到对应公网 IPv4：

- `linux2.wayuwa.cn` -> Linux 主节点所在出口；
- `linux1.wayuwa.cn` -> Linux1 从节点所在出口；
- `wao.wayuwa.cn` -> `8.136.152.131`。

家庭路由器需要 TCP 端口映射：

|       端口 | 主节点 | 从节点 | 用途                    |
| ---------: | :----: | :----: | ----------------------- |
|         22 |   是   |   是   | SSH / CI/CD             |
|       3306 |   是   |   是   | MySQL 主从与运维        |
|       6379 |   是   |   是   | Redis 主从              |
|      26379 |   是   |   是   | Redis Sentinel          |
|       8080 |  可选  |  可选  | App 直连诊断；Gateway 不依赖公网 8080 |
|       9000 |   是   |   是   | Gateway / 云 Nginx 上游 |
|       9876 |  可选  |  可选  | DDNS-Go 管理            |
| 8848、9848 |   是   |   否   | Nacos HTTP 与 gRPC      |

云安全组只需放行 22、80 和 443。公网暴露家庭节点数据服务的风险已由项目所有者明确接受；仍必须使用生成脚本产生的强随机密码，不得使用示例值。

## 节点初始化与启动

Linux 主节点安装 Docker Engine 与 Compose 插件：

```bash
sudo sh deploy/scripts/install-docker-engine.sh
sh deploy/scripts/generate-gateway-cert.sh
sh deploy/scripts/deploy-linux.sh master
```

当前娃弟主机已经运行 MySQL 9.7、Nacos 与 DDNS-Go，不应启动重复实例。该节点的私有 `.env` 使用：

```dotenv
MANAGE_MASTER_INFRA=false
MYSQL_HOST=host.docker.internal
NACOS_SERVER_ADDR=host.docker.internal:8848
MASTER_BIND_IP=192.168.31.180
```

这种模式下 Compose 只管理 WAO 自己的 Redis、Sentinel、App 和 Gateway。没有既有基础设施的新主机才设置 `MANAGE_MASTER_INFRA=true`，由 `managed-infra` profile 一并启动 MySQL、Nacos 与 DDNS-Go。

Gateway 以非 root 用户运行。部署脚本会将加密的 `gateway.p12` 调整为 `0644` 以便容器读取；私钥仍由私有 `.env` 中的强随机 `GATEWAY_SSL_PASS` 保护，`.env` 本身保持 `0600`。

安装脚本会把当前登录用户加入 `docker` 组；重新登录后再执行部署。主、从节点部署脚本都会为当前用户安装每五分钟运行的公网 IP 刷新任务。

Linux1 从节点使用原生 Docker Engine。首次准备：

```bash
cd ~/workspace/wao
sudo sh deploy/scripts/install-docker-engine.sh
# 重新登录后
docker info
```

Jenkins 向 Linux1 的 `~/deploy/incoming` 发送 tar.gz 发布包、镜像归档、共享环境和 Gateway 证书，随后由 `install-release-linux.sh` 在部署锁内更新 `~/deploy/wao`。CD 开始发布前会验证专用 SSH 密钥、主机指纹和 Docker；任一检查失败时保持从节点 `down`、保留 Linux2 当前版本并将流水线判为失败。每个应用节点最多自动部署三次。MySQL 从库只复制 `wao.%`，不会同步 Linux2 上的其他业务库。

## DDNS 与公网 IP 变化

主、从 Compose 都包含固定版本的 DDNS-Go。首次启动后从局域网访问对应节点的 `http://<节点局域网地址>:9876`：

- Linux 主节点配置 `linux2.wayuwa.cn`；
- Linux1 从节点配置 `linux1.wayuwa.cn`；
- DNS 服务商 Token 只保存在各自 `ddns-go-data` 数据卷，不写入 `.env`、Git 或 CI。

Linux2 主节点使用 `refresh-public-ip.sh master`，Linux1 从节点使用 `refresh-public-ip.sh slave`。公网地址变化时脚本会刷新 Nacos 中的实例注册地址。Linux1 另有独立 DDNS 程序维护 A/AAAA 记录并发送飞书变更通知。

## 云节点

先确认 `wao.wayuwa.cn` 已解析到云服务器且 80/443 可访问。将代码放在 `/opt/wao` 后执行：

```bash
sudo sh deploy/scripts/provision-cloud-node.sh <证书通知邮箱>
```

脚本只安装 Nginx 与 Certbot，签发 TLS 证书，安装健康检查与 Nginx DDNS 激活任务，并验证证书续期。云节点不安装 Docker、不运行数据库或 Sentinel；前端生产制品放在 `/var/www/wao/dist`。证书签发前的 HTTP 引导配置会提供静态前端；证书签发后，如果两个 Gateway 健康端点均未就绪，则使用 HTTPS 待后端模式并让 `/api/` 返回 503。定时任务每分钟解析两个 DDNS 域名的 IPv4，只有地址变化时才重载 Nginx；即使 DDNS 同时发布不可达的 AAAA，也不会把 IPv6 加入上游。检测到至少一个 Gateway 健康端点可用后，会自动切换到正式双 Gateway 配置。CD 会用 `set-nginx-upstream-state.sh` 在应用节点发布前临时摘流；摘流锁存在时 DDNS 定时任务不会覆盖临时状态。

## JumpServer 资源管理

JumpServer CE 固定运行在 Linux 主节点 `/opt/wao-jumpserver`，使用独立 PostgreSQL、Redis 和数据卷；Web
只绑定 `127.0.0.1:18080`，经现有 Jenkins 反向 SSH 通道映射到云节点 `127.0.0.1:18082`。云 Nginx 仅从
`https://jms.wayuwa.cn` 暴露服务。首次发布前需把 `jms.wayuwa.cn` 解析到云入口 `8.136.152.131`；CD 会先
严格核对解析，再签发独立 Let's Encrypt 证书。Linux 主节点使用 JumpServer 安装器内置的阿里云镜像源
拉取社区版应用镜像，避免首次部署依赖跨境 Docker Hub 链路；Redis、PostgreSQL 等基础镜像仍按安装器规则处理。

主发布脚本会幂等生成 WAO 与 JumpServer 的共享 SSO 密钥、部署社区版 Custom SSO、生成独立资产 SSH
密钥，并把公钥安装到 Linux2、Linux1 和云节点。私钥只保存在 `/opt/wao-jumpserver` 受限目录和 JumpServer
账户数据中，不复用 Jenkins 发布私钥；中断后重跑会忽略旧固定暂存文件，在唯一临时目录重新生成并原子安装，
不会等待交互式覆盖确认。已登录成员从「工具 → 资源管理」进入；管理员映射为系统管理员，普通
成员只建立系统用户身份，仍需在 JumpServer 中显式配置资产授权。
主发布链路会维护仅包含娃哥、娃弟的「WAO 基础设施维护者」组：该组通过专用 SSH 密钥访问三项 Linux 资产。
旧 Windows1 资产与 RDP 授权会被幂等停用，Linux1 不开放 RDP。
发布脚本检测到 JumpServer Core 容器被替换时会同步刷新无状态 Web，避免 Nginx 继续缓存旧 Core 地址；Core
未变化时不会额外重启 Web。脚本还会在 Core 启动后只给数据根目录补充目录穿越权限，使非 root Web Nginx
能读取公开的登录静态资源而不能列举数据根目录；生产验收会实际读取登录脚本，避免健康接口正常但页面资源
全部 403。资源管理默认仍用新页面打开，显式切换到 WAO 页签时只允许 `https://wao.wayuwa.cn` 嵌入。

日常验证：

```bash
curl --fail https://jms.wayuwa.cn/api/health/
sudo -u wadi /usr/local/lib/wao-jenkins/deploy-master-local.sh \
  --verify-jumpserver-assets "$(xdg-user-dir DESKTOP)/deploy/wao"
```

第二条命令会同时验证三项 SSH、娃哥/娃弟组成员、SSH 资产授权，以及旧 Windows/RDP 资产已停用；任一项未收敛都会让发布失败。

备份至少包含 `/opt/wao-jumpserver/config` 与 `/opt/wao-jumpserver/data`，并在停止 JumpServer 写入后制作一致性
快照；恢复演练必须同时验证登录、三项资产和专用 SSH 账户。升级必须先核对官方发行说明和社区版镜像，再修改
固定版本与校验值，禁止直接跟随 `latest`。具体边界见 [ADR-143](../docs/adr/143-jumpserver-isolated-community-sso.md)。

## 数据、备份与验收

MySQL 从库只在数据卷首次初始化时自动配置 GTID 复制。MySQL 9 的复制密码最多 32 个字符，`new-deployment-env.ps1` 已为该字段生成 32 位十六进制随机值。验证：

```bash
sh ~/deploy/wao/deploy/scripts/validate-slave.sh
```

如果主库已经清理了从库所需的早期 binlog，先用 `mysqldump --single-transaction --set-gtid-purged=ON` 生成当前 `wao` 快照，再执行 `bootstrap-mysql-slave.sh <快照.sql.gz>`。该脚本只重置从库 GTID，不修改主库业务数据。

Linux1 使用 cron/systemd 调用 `backup-mysql.sh` 和 `restore-drill.sh`；备份位于 `~/deploy/wao/deploy/backups`，默认保留 7 天。

最终入口应为：

```text
https://wao.wayuwa.cn/api/hello
```

应返回 HTTP 200、`X-Request-Id` 响应头和 `code=0` 的统一 JSON；随后检查 MySQL 复制、两个 Sentinel 的 master 视图和 Nacos 中的两台应用实例。

当前只有主、从两个 Sentinel。它们可提供监控和客户端发现，但任一整机离线后无法形成可靠多数派，因此不承诺整机故障下的 Redis 自动切换。后续如需自动故障转移，应增加独立的第三仲裁节点。

## 图床

用户上传的图片（任务 / 子任务描述里的截图）存在主节点 MySQL 的 `image_blob` 表，不落容器本地目录——主从两个节点的 `wao-server` 都连主节点的 MySQL，所以落库天然满足「图床在主节点上」，容器重建与节点故障转移都不丢图，也不需要额外的 volume 或 Nginx 定向路由。理由见 `docs/adr/012-markdown-rendering-and-image-store.md`。

单张上限 **10MB**，这个数字在三处必须保持一致，改一处就要同步另外两处：

| 位置 | 配置 |
| --- | --- |
| 应用 | `ImageService.MAX_BYTES` / `spring.servlet.multipart.max-file-size` |
| 网关 | `spring.codec.max-in-memory-size: 16MB` |
| 云 Nginx | `location /api/` 的 `client_max_body_size 52m`（通用文件上限 50MB，留 2MB 给 multipart 边界） |

云节点更新 Nginx 配置后记得 `nginx -t && systemctl reload nginx`，否则上传大图会返回 413。

桌面宠物安装包和签名更新清单独立保存在 `/var/www/wao-downloads/desktop`，由 `WAO Desktop Release` 手动发布；普通 Web CD 只更新 `/var/www/wao/dist`，不会删除历史安装包。`latest.json` 禁止缓存并在所有更新包上传后最后原子替换，版本化更新包允许长期缓存。

额外同域应用的静态路由仍可放在 `/etc/nginx/wao-external-apps.d/*.conf`。自媒体 Agent 已并入 WAO 主仓库和主 CD，随主站一起更新 `/var/www/wao-agent/dist` 与 `wao-agent-server`；游戏已进入原生 `/games`，不再依赖 `/game/` 独立入口。目录继续保留给真正需要独立路由的第三方或后续模块。

图片正文进了 MySQL，`backup-mysql.ps1` 的备份体积会随图片增长，留意 `deploy\backups` 的磁盘占用。

## 任务分享链接

任务分享页 `/s/t/<taskId>` 与 Markdown 直读地址 `/api/share/tasks/<taskId>.md` 都是**公开免登录**的：拿到链接就能读到该任务的标题、描述、图片与子任务（负责人、评论、活动不外泄）。当前任务 id 是可枚举的时间戳，因此这套分享**不适合在有外部成员的正式环境启用**，收敛方案（不可猜的 share token）记在 `docs/adr/013-public-task-share-links.md`。

## Jenkins CD

主站 `main` 由 Linux 主节点上的 Jenkins 构建和发布，GitHub 只负责仓库、签名 Webhook 和提交状态。
Jenkins 使用 Linux1、云节点两套独立 SSH 密钥与严格 `known_hosts`；生产 `.env`、`.env.secrets` 和网关证书
以 Linux2 主节点私有部署目录为准。CD 一次构建带 Git SHA 标签的镜像，按 Linux1 → Linux2 → 云前端顺序
滚动发布；两台应用节点均运行同一 SHA 且通过直连验证后才标记成功。首次重建从库时在 Jenkins 手工运行
`wao-main-cd` 并勾选 `BOOTSTRAP_SLAVE`，正常发布不得自动重置已有从库。安装与恢复见
[Jenkins runbook](jenkins/README.md)，决策见 [ADR-099](../docs/adr/099-self-hosted-jenkins-delivery.md)。
登录 WAO 后可从左下角「链接 → Jenkins」查看构建与部署记录；公网入口继承 WAO 成员身份，不再要求或分发
Jenkins 管理员密码。普通成员只读，WAO 管理员和超级管理员才获得 Jenkins 管理权限。

发布包用 `git archive HEAD` 生成 tar.gz，落到节点上时**先清空上一版源码再解压**：`tar -xzf` 只覆盖同名文件，不会删除这一版里已经删掉或改过名的文件，留在节点上的旧文件会跟着进 Docker 构建上下文。2026-07-26 连续四次部署回滚就是这么来的：`V18__chat_sync_monthly_archive.sql` 在仓库里已经改名成 V21，节点上的旧副本还在，Flyway 一启动就报「Found more than one migration with version 18」。清理范围是节点目录下除 `deploy/` 以外的全部内容——`deploy/` 里放着节点私有的 `.env`、`.env.secrets`、`certs/`、`data/`，其中被 git 跟踪的文件随即被这次解压整份覆盖。

## 飞书登录

将飞书 App ID、App Secret 与现有 `WAO_VAULT_KEY` 保存在 Linux 主节点私有的 `deploy/.env.secrets`。
Jenkins 会把同一文件下发到 Linux1，不要提交 Git 或写入 Jenkins 日志：

```dotenv
FEISHU_LOGIN_ENABLED=true
FEISHU_APP_ID=<飞书 App ID>
FEISHU_APP_SECRET=<飞书 App Secret>
FEISHU_REDIRECT_URI=https://wao.wayuwa.cn/api/auth/feishu/callback
FEISHU_FRONTEND_URL=https://wao.wayuwa.cn
FEISHU_COMMENT_WEBHOOK_URL=<飞书群机器人 Webhook>
AUTH_PROTECTION_ENABLED=true
SESSION_COOKIE_SECURE=true
```

在飞书开发者后台的安全设置中添加同一个重定向 URL。网页应用的工作台在线网页主页配置为 `https://wao.wayuwa.cn/api/auth/feishu/workbench`；PC 端“跳转至浏览器打开新页面”配置为 `https://wao.wayuwa.cn/api/auth/feishu/browser`。修改 Secret 后手动运行一次 Jenkins `wao-main-cd`，或随下一次 `main` push 自动下发并重建 `wao-server` 容器。

微信扫码登录需在微信开放平台创建“网站应用”，将授权回调域配置为 `wao.wayuwa.cn`，并在 Linux 主节点私有 `deploy/.env.secrets` 中配置 `WECHAT_APP_ID`、`WECHAT_APP_SECRET`。CD 会自动下发以下配置：

```dotenv
WECHAT_LOGIN_ENABLED=true
WECHAT_REDIRECT_URI=https://wao.wayuwa.cn/api/auth/wechat/callback
WECHAT_FRONTEND_URL=https://wao.wayuwa.cn
```

登录页的“微信”入口使用认证公众号，不依赖上面的网站应用资质。先在 WAO“系统设置 → 系统登录配置”录入
App ID、App Secret、独立随机 Token 并保存，再到公众号后台「设置与开发 → 基本配置」使用页面给出的服务器地址，
消息加解密方式选择明文模式。以下私有环境变量只作为首次迁移和紧急回滚兼容来源（不要提交或输出到日志）：

```dotenv
WECHAT_OFFICIAL_LOGIN_ENABLED=true
WECHAT_OFFICIAL_APP_ID=<认证公众号 App ID>
WECHAT_OFFICIAL_APP_SECRET=<认证公众号 App Secret>
WECHAT_OFFICIAL_TOKEN=<公众号服务器配置 Token>
```

公众号需要真实具备生成带参数二维码和接收消息权限。启用后用未关注、已关注两种微信账号分别验证：扫码后页面从
“等待微信扫码”变为“等待公众号收到验证码”，发送正确验证码后浏览器进入 WAO；错误验证码、过期二维码、伪造签名
均不得登录。未配置时页面会说明所缺条件，飞书和邮箱仍可使用。

## 密钥库加密密钥

`WAO_VAULT_KEY` 是密钥库密码字段的 AES-256-GCM 加密密钥（base64 编码的 32 字节），主、从两个节点必须使用**同一个值**（`docs/adr/009-vault-server-side-encryption.md`）。缺失时 `wao-server` 会拒绝启动（`VaultCryptoService` 主动校验密钥长度），CD 会自动回滚到上一个能启动的镜像，不会造成线上不可用，但会一直部署失败直到补上这个变量：

```bash
# 生成一次，写进两个节点共享的同一份 deploy/.env（不要提交 Git）
openssl rand -base64 32
```

```dotenv
WAO_VAULT_KEY=<上面生成的值>
```

写入后在各节点重新执行发布脚本重建 `wao-server` 容器；新装机场景下 `deploy/scripts/new-deployment-env.ps1` 已经会自动生成这个值，不需要手动补。

## 网易云音乐官方开放平台

播放器的网易云来源只走官方开放平台 OAuth 与音乐 API。先在网易云音乐开放平台创建并审核应用，申请用户资料、红心歌单、歌单歌曲、播放地址、令牌刷新和播放数据回传能力，并把公开回调精确配置为：

```text
https://wao.wayuwa.cn/api/music/netease/callback
```

审核通过后，将 `.env.example` 中全部 `NETEASE_MUSIC_*` 项写入主、从节点私有的 `deploy/.env`；APP Secret 和单行 Base64 编码的 PKCS#8 RSA 私钥不得提交到 Git。`CHANNEL`、`DEVICE_TYPE`、`OS`、`OS_VER`、`APP_VER`、`BRAND`、`MODEL` 必须使用开放平台审核或分配的真实值，不能照抄示例。最后才设置 `NETEASE_MUSIC_ENABLED=true` 并重新部署。配置不完整时服务会保持禁用，现有 WAO 文件播放器不受影响。

功能和视觉稿还需按开放平台流程完成产品/品牌审核，并用两个测试 UID、两个稳定 device id 完成设备与播放数据验收后再正式上线。浏览器能提供网络类型时 WAO 会传真实的 `wifi/2g/3g/4g/5g`；浏览器不开放该能力时按 `wifi` 兜底，验收时应在目标浏览器和实际网络上核对上报值。

每个 WAO 成员需要在播放器中单独点击“绑定我的账号”。令牌按成员加密保存；解除绑定只删除当前成员记录。官方文档入口：[H5 OAuth](https://developer.music.163.com/st/platformDoc?docId=78eb8f6bec12427d90b120e21b569b16)、[公共参数与设备信息](https://developer.music.163.com/st/platformDoc?docId=0f7801d7d6d24180b8fc9058d1ffe593)、[播放数据回传](https://developer.music.163.com/st/platformDoc?docId=eb0ddaf2efc649e99dffe0677472466a)。

- `MASTER_HOST=linux2.wayuwa.cn`，`SLAVE_HOST=linux1.wayuwa.cn`，云节点使用固定公网地址；
- Jenkins 在 Linux2 本机构建并发布主节点；Linux1 主动建立仅监听 Linux2
  `127.0.0.1:2201` 的反向 SSH 通道，Jenkins 通过该通道和专用部署密钥发布从节点；
- Maven 和 Node 测试使用固定工具容器；四个运行镜像一次构建后，本机直接复用并压缩传给 Linux1；
- `slave-known-hosts` 与 `cloud-known-hosts` 必须保存人工核验后的 SSH 主机公钥；
- 密码登录仅用于首次安装公钥，流水线只使用 `/etc/jenkins/secrets` 中的专用部署密钥；
- 飞书 Webhook 保存在主节点 `deploy/.env.secrets`，同时用于 main 部署结果和 WAO 任务新评论通知。
- `FEISHU_COMMENT_WEBHOOK_URL` 现在只是首次接管前的**兜底**：系统通知统一读「设置 → Webhook配置」里标记为系统默认的机器人（加密存 `feishu_webhook_config`，见 ADR-089）。首次打开配置页会把仅存在于环境变量的旧地址加密迁入；用户删空注册表后不会再回落恢复它。
