# 本机 Codex 额度采集

依据 ADR-307，补充旧 [会员与重置卡规格](../specs/phase-1-codex-membership-reset-cards.md) 的独立采集方式。手工日期 API 不变。

## 启用

由运行 WAO 服务的操作系统账号先登录 Codex ChatGPT 账号，验证 `codex login status`。服务和 Codex 必须看到同一个登录目录；浏览器无法直接读取访问者电脑上的登录。仅在已获授权展示该账号额度的 WAO 实例启用。

```text
WAO_CODEX_QUOTA_ENABLED=true
WAO_CODEX_QUOTA_EXECUTABLE=<本机 codex 或 codex.exe 的绝对路径>
WAO_CODEX_QUOTA_SOURCE=windows1
```

配置在服务启动时读取。默认关闭；采集频率 60 秒，历史阈值 15 分钟。全局 `wao.scheduling.enabled=false` 时无定时采集，但已登录成员查询 `/api/codex-memberships` 会按同一节流规则补采。首次读取或刷新失败可在一分钟后重试；历史内容明确标为历史快照。

Linux2 使用同账号时，将来源改为 `linux2`，设置该环境的 CLI 路径及登录目录。共用数据库自动按账号取最新值，Windows 停机后 Linux2 可继续采集，不需要手工搬运额度数据。未安装 CLI 的云端服务保持关闭，仍可读取共享数据库中的快照。各节点若使用不同数据库，需要现有数据同步路径，本功能不复制数据库、登录文件或认证 Token。容器还须让服务运行身份具备可执行程序、登录目录和网络出口；仅设置开关不会自动完成这些前提。

## 字段与故障

- 官方接口：`account/read`、`account/rateLimits/read`。优先 `rateLimitsByLimitId`，旧版回退 `rateLimits`；`usedPercent` 是已用比例。首页展示 `codex` 的 10080 分钟窗口，详情显示其他窗口。
- `resetsAt` 为 Unix 秒，浏览器按本地时区展示；它不是 Pro 订阅到期日。
- `rateLimitResetCredits.availableCount` 是官方数量，不用金额余额或明细长度推算；缺失与 0 区分。
- 不支持 API Key 登录作为 ChatGPT 额度账号；不读取认证文件、不自动登录、不调用重置或购买接口。仅持久化允许的展示字段。
- 请求失败保留已有快照并标历史；无快照时提示检查登录和网络。CLI 不存在、接口不兼容或代理故障时先由服务账号修复，不恢复旧 Runner。
- 停用采集设置开关为 false 并重启服务；历史保留并自然过期，不清理用户数据。

## 验收

```powershell
mvn -f wao-server/pom.xml '-Dtest=LocalCodexQuotaProcessTest,LocalCodexQuotaServiceTest,CodexQuotaServiceTest,CodexMembershipServiceTest' test
# 显式运行真实账号只读探测；仅输出窗口组数量和重置卡字段是否存在。
mvn -f wao-server/pom.xml '-Dtest=LocalCodexQuotaProcessTest' '-Dwao.codex.live=true' '-Dwao.codex.executable=<绝对路径>' test
```

连接现有业务数据库的独立本地服务启用采集，浏览器登录后检查首页、打开详情、刷新、关闭及窄屏展示。确定性测试覆盖多机同账号去重、失败保留、空字段、零张、节流和过期。Linux2 未实际启用前不能声称其采集已验证。

官方协议依据：[Codex app-server](https://learn.chatgpt.com/docs/app-server)。
