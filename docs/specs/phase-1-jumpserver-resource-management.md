# Phase 1：JumpServer 统一资源入口与 WAO 登录

对应任务：`t1787813213986`（在现有资源上部署 JumpServer，并纳管 WAO 当前节点）、`t1787837409518`
（授予娃哥、娃弟资产权限并补齐 Windows 图形连接）。

## 用户结果

- 登录 WAO 的成员能从「工具」分组的新菜单「资源管理」进入 `jms.wayuwa.cn`，不再输入第二套
  JumpServer 密码；入口保留独立菜单，后续可在「设置 → 菜单与快捷链接」隐藏、改名或调整顺序。
- WAO 管理员和超级管理员进入 JumpServer 后是系统管理员；普通成员只获得系统用户身份，资产访问仍由
  JumpServer 授权规则显式授予，不能因为拥有 WAO 账号自动取得全部主机权限。
- JumpServer 首次部署即登记 Linux 主节点、Windows 从节点和阿里云入口三项真实主机资产，并使用独立的
  `wao-jumpserver` SSH 密钥账户；不得复用 Jenkins 发布私钥或写入演示凭据。
- 娃哥、娃弟进入 Web 终端后能看到并通过专用密钥连接三项 SSH 资产；Windows 从节点额外提供 RDP 3389
  图形连接，连接时手动输入真实 Windows 凭据，不把既有系统密码复制进 Git 或 JumpServer 自动化配置。

## 部署与网络

- 固定使用 JumpServer `v4.10.19-ce`。JumpServer Core、Celery、KoKo、Lion、Chen、Web 以及它自己的
  PostgreSQL 16、Redis 7 运行在 Linux 主节点的独立 Compose 项目中，持久数据位于
  `/opt/wao-jumpserver`，不进入 Git，也不复用 WAO 的 MySQL/Redis。大陆节点通过安装器官方
  `DOCKER_IMAGE_MIRROR` 开关使用其内置阿里云镜像前缀，现有配置也必须在每次部署时幂等收敛。
- JumpServer Web 只绑定 Linux loopback `127.0.0.1:18080`；独立 systemd 反向 SSH 隧道把它映射到云
  Nginx 的 `127.0.0.1:18082`。公网只开放云节点的 `https://jms.wayuwa.cn`，Core、数据库、Redis 和容器
  端口不直接暴露。
- `jms.wayuwa.cn` 使用指向 `wao.wayuwa.cn` 的 CNAME 和独立 Let's Encrypt 证书。证书签发前必须确认解析
  已指向云入口；复用已有证书前还必须用域名校验确认其覆盖 `jms.wayuwa.cn`，文件存在但域名不匹配时由
  Certbot 非交互重签。解析或证书不正确时发布失败关闭，不能回退到 HTTP 或忽略 TLS 校验。
- 主站部署先保持 Windows 节点在线，再部署 Linux 上的 WAO 与 JumpServer，之后安装三台资产的专用公钥，
  最后更新云 Nginx。资产密钥生成必须支持失败后非交互幂等重跑，不得因持久目录中的中间文件等待覆盖确认。
  任一步失败都保留原 WAO 服务，并把 JumpServer 发布判为失败。
- CD 幂等启用 Windows 远程桌面服务，并只允许 JumpServer 主节点当前解析出的 IPv4 访问 TCP 3389；不得启用
  面向所有来源的远程桌面防火墙规则。每次发布重新收敛来源地址，以适配主节点动态公网地址变化。

## 单点登录与安全

- JumpServer 官方 OIDC 属于 X-Pack，本次不伪装企业能力；使用社区版官方 Custom SSO 扩展点。
- `/api/auth/jumpserver` 只接受有效 WAO Session。服务端生成 45 秒有效的 HMAC-SHA256 票据并 302 跳转到
  JumpServer Custom SSO 登录地址；票据含稳定成员 id、姓名、邮箱和服务端派生角色，不接受浏览器提交角色。
- JumpServer 校验签名、签发和过期时间，并通过自己的 Redis 原子登记 nonce，保证票据只能使用一次。共享密钥
  首次发布时在主节点生成，只保存在 `deploy/.env.secrets` 和 JumpServer 私有配置中，不写日志、URL 文档或 Git。
- WAO Session 失效时不能生成新票据。JumpServer 已建立的会话按自身会话期限退出；不扩大 WAO Cookie 到
  `.wayuwa.cn`，避免把主站会话暴露给所有子域。
- JumpServer 默认组织内固定维护「WAO 基础设施维护者」用户组，仅将 SSO 身份名称为娃哥、娃弟的 `wao_`
  用户加入该组；三项 SSH 授权和 Windows RDP 授权均绑定该组。其他普通成员仍不自动获得任何资产。

## 状态与验收

- 外部应用注册表从旧版本升级时自动补上「资源管理」；用户在新版本明确删除后不得反复恢复。
- 未登录请求 `/api/auth/jumpserver` 返回 401；已登录请求只跳向固定的
  `https://jms.wayuwa.cn/api/v1/authentication/custom-sso/login/`，不得接受开放重定向。
- 可配置的页签模式使用主站同源 `/api/external/jumpserver/health` 探针，不依赖 JumpServer 跨域 CORS 配置。
- 本地测试覆盖票据签名、有效期、角色映射、篡改和重放拒绝，覆盖注册表旧版本补齐与明确删除。
- 生产验收至少证明：JumpServer 全部社区组件健康，公网证书有效，WAO 菜单已出现，未登录 SSO 被拒绝，
  三项资产及其独立 SSH 账户已持久化，从 JumpServer 主机使用该专用密钥逐项建立 SSH 连接；娃哥、娃弟
  均属于维护者组并能看到三项资产；Windows 资产登记 `rdp:3389`，且 JumpServer 容器能建立 3389 TCP 连接。

## 明确边界

- 不启用 X-Pack、OIDC、企业客户端或企业远程应用能力。
- 不自动给其他普通成员授予资产；娃哥、娃弟的维护者组授权由发布链路按本规格显式维护。
- 不复制现有系统密码、Jenkins 私钥、WAO Vault 密钥或云厂商 Secret 到 JumpServer。
