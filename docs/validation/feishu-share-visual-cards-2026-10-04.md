# 飞书分享图文卡片本地验证

日期：2026-10-04。使用独立工作区，基于 `origin/main`，保留主工作区的其他需求改动。

## 已验证

- 定向服务端回归共 166 项：164 项通过，2 项真实集成检查跳过，0 失败。范围包含 `FeishuLinkPreviewServiceTest`、`ShareServiceTest`、`FeishuQuickCollectServiceTest`、`TianrenReadingServiceTest`、`FeishuOpenApiClientTest`、`FeishuWebhookNotifierTest`、`FlywayMigrationsTest` 及显式开启的 `FeishuShareCardsAcceptanceTest`。
- 小记有效润色版本公开投影、过期来源与其他作者拒绝；首张图片组件、240 字正文、去除图片语法、原标题及原链接；旧存档与外部图片边界。
- 卦象快速总结小节、旧存档正文开头、私有配图提供者的解读一致性、失败降级；图片慢上传在回调预算内退回文字，完成后复用同一上传结果。
- `local-cloud` 在既有本机 MySQL 上启动真实 Spring 服务上下文，通过公开小记 HTTP 读取及 `/api/feishu/events` 回调，核对正文一致性、标题和卡片跳转。没有新增模型运行、生成图片或向会话发送消息。
- 工程一致性检查通过，0 错误；保留 5 项历史 ADR 重号警告。未新增 migration 或 ADR 编号。

执行命令：

```powershell
mvn -q -pl wao-server -am test '-Dtest=FeishuShareCardsAcceptanceTest,FeishuLinkPreviewServiceTest,ShareServiceTest,FeishuQuickCollectServiceTest,TianrenReadingServiceTest,FeishuOpenApiClientTest,FeishuWebhookNotifierTest,FlywayMigrationsTest' '-Dfeishu.share.eval=true' '-Dsurefire.failIfNoSpecifiedTests=false'
python deploy/scripts/check-engineering-consistency.py --base origin/main
git diff --check
```

验收只从既有私有环境文件读取连接变量；测试对真实数据不打印正文、分享凭据或飞书密钥。普通回归未指定 `feishu.share.eval` 时不会连接 MySQL 或上传图片。

## 未验证与依赖

- 当前可用配置指向本机 MySQL，并非项目要求的线上数据库；没有将本机数据验收标称为生产数据验收。
- 本机数据库没有飞书应用凭据，真实飞书图片上传检查跳过；图片 API 协议和上传失败/缓存行为只有自动化测试证据。
- 本机数据库没有已分享卦象，真实卦象分享回调检查跳过；没有擅自为真实成员创建公开分享凭据。
- 「设计卦象图片生成 Demo」目前完成设计与交接文档，私有图片生产者尚未实施。本次提供 `TianrenShareVisualProvider` 协议与消费端，真实卦象配图联调仍待领域服务实现。细节见 [图文卡片规格](../specs/feishu-share-visual-cards.md)。
- 飞书客户端实际渲染、正式应用 URL 规则及按钮体验未验证；没有发送测试群消息，也不宣称已上线。

原作者验收时因此保留草稿 PR，等待配图提供者与真实环境验收。

## 最近七天 PR 合并复验

用户随后明确授权合并最近七天 PR 并部署本机 WAO。此次在新的 `pr1153-verification` 独立工作区合并 `origin/main` 的 `6113cadc1cde798b7843766220c6acfdaee8b969` 后复验，未修改原工作区和部署工作区。

- 当前定向测试共 178 项，177 项通过、1 项跳过，0 失败。原命令额外加入 `TianrenVisualServiceTest`，继续显式开启 `feishu.share.eval=true`。
- 既有本机 MySQL 的真实小记公开正文、飞书校验回调和原链接通过；数据库现已有卦象分享凭据，其真实公开事实、回调卡片及详情入口也通过。验收不生成内容、不创建分享凭据、不发送会话消息。
- 唯一跳过项为真实飞书图片上传：既有本机数据库未配置飞书应用凭据。线上数据库、飞书正式应用 URL 规则与客户端渲染仍未验证。
- main 的 ADR-320 已交付私有配图生产者，但明确首版不扩展公开分享图片；`TianrenVisualService` 尚未实现本 PR 的 `TianrenShareVisualProvider`。现有文字卡片与旧公开配图继续工作，私有图片分享适配仍未接通，不扩大本次合并范围。
- 最新 main 无文本冲突；工程一致性退出码 0，0 错误、5 项历史 ADR 重号警告，`git diff --check` 通过。本 PR 不新增 migration 或 ADR，V181 来自已合并的私有配图需求。

按这次明确授权，以已实现的小记卡片、卦象摘要及分享消费接口解除草稿并合并；保留上述缺项，不宣称私有卦象图已在飞书展示。
