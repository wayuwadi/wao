# ADR-143：隔离部署 JumpServer CE 并使用一次性 WAO SSO 票据

## 状态

已采纳。

## 背景

WAO 需要把 Linux 主节点、Windows 从节点和阿里云入口集中纳管，并让成员沿用现有 WAO 登录。JumpServer
官方 OIDC 是企业版功能；直接宣称社区版具备 OIDC 会形成无法工作的配置。把 JumpServer 直接并入 WAO
MySQL、Redis 或公开主节点端口，也会让运维系统故障扩大到业务数据和家庭网络入口。

现有 Jenkins 采用同域反向代理身份头，但 `jms.wayuwa.cn` 是独立子域，WAO 的 host-only Session Cookie 不会
发送过去。把 Cookie Domain 扩到 `.wayuwa.cn` 虽然能复用 `auth_request`，却会让所有同级子域收到主站会话，
扩大凭据暴露范围。

## 决策

- 在 Linux 主节点隔离运行固定版本 JumpServer CE 及其 PostgreSQL/Redis，不复用 WAO 业务数据服务。Web
  仅监听 loopback，经独立反向 SSH 隧道和云 Nginx 暴露 `jms.wayuwa.cn`。
- 使用 JumpServer CE 已提供的 Custom SSO 扩展。WAO 为当前会话生成短时 HMAC 票据；JumpServer 校验固定
  受众、签发时间、过期时间和一次性 nonce 后创建或恢复用户会话。
- WAO 管理员映射 JumpServer `SystemAdmin`，普通成员映射 `SystemUser`。资产权限继续在 JumpServer 内显式
  配置，不把“能登录”解释为“能管理所有主机”。
- 三台现有主机使用本次生成的独立 SSH 密钥纳管。公钥通过已有 Jenkins 到 Windows/云节点的受信通道安装，
  私钥只保存在 JumpServer 加密账户和主节点受限持久目录；不得复用 Jenkins 发布密钥。
- 外部应用注册表新增默认可见的「资源管理」菜单，以 `/api/auth/jumpserver` 为入口并用新页面打开。注册表
  版本升级只补一次，后续尊重用户隐藏、改名、调序或删除。

## 后果

成员从 WAO 菜单进入 JumpServer 不需要第二套密码，主站 Cookie 仍保持 host-only；JumpServer 故障不会停止
WAO API 或污染业务库。代价是主节点新增约 8 GiB 的运维负载和一套独立备份对象，且 WAO 退出不会主动撤销
已经建立的 JumpServer 会话。若以后采购 X-Pack 并需要统一注销，应另写 ADR 迁移到标准 OIDC，而不是在本
实现上静默改变票据协议。
