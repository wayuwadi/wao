# 本机 Codex 动态模型目录

2026-10-03，补充 ADR-306。用户已要求新模型能自动出现在小记选择器，已保存的选择不得随默认模型变动。

- `GET /api/assistant/codex-models` 仅登录成员可读，返回 `models[{id,label,reasoningEfforts}]`、`updatedAt`、`stale`、`message`。思考深度来自实际模型目录的 `supportedReasoningEfforts`，缺失时兼容显示 `medium`。未登录不启动 CLI；不返回邮箱、凭据、路径或隐藏模型。
- 通过实际配置的 Codex `app-server` 读取 `account/read` 与分页 `model/list`，只接受现有 ChatGPT 登录及非隐藏模型。不是从网页、静态名单或模型名称推测账号权限。
- 打开设置时自动读取；设置保持打开时每分钟读取。服务端成功目录缓存五分钟，失败退避一分钟，并保存最后成功目录到本机状态目录。过期失败明确展示，刷新成功后自动恢复。
- 新模型不需要再次修改前端或服务端枚举。现有默认仍为 GPT-6 Astra，用户选择精确保留；目录缺少所选模型时显示保留状态，不静默换模型。提交执行时按本机目录校验，无法确认模型给出可操作错误。
- 历史目录只表示曾经可见；模型实际调用仍可能因网络、额度、下架或工具兼容性失败，走既有错误与重试流程。文本输入能力或目录可见不代表已验证每个模型都能生成图片。
- 模型发现不执行生成、不启用 Runner、不增加付费 API。CLI 安装升级是独立维护动作，不把刷新目录宣称为完成 CLI 升级。
- 此接口用于现有浏览器个人 AI 设置。工作区 MCP 没有本机账号运行环境读取权限，不新增 CLI/MCP 工具或扩大已有令牌；将来终端需选择执行模型时重审授权与目录暴露范围。

官方协议依据：[Codex app-server 的 Models 章节](https://learn.chatgpt.com/docs/app-server#models)。模型列表必须使用实际客户端与账号返回值，不能硬编码文档示例。

## 回归

```powershell
mvn -pl wao-server -am test '-Dtest=LocalCodexModelServiceTest,LocalCodexModelControllerTest,LocalCodexQuotaProcessTest,AiAssistantServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
cd wao-web
npm run test:unit -- --run src/__tests__/ArticleImageModels.spec.ts src/__tests__/autoNoteEnrichment.spec.ts --maxWorkers=2
```

服务端测试附加 `-Dwao.test.codex=<已登录的 codex.exe 绝对路径>` 时运行真实只读目录检查，不发起模型调用。人工验收需在本地真实应用登录专用账号，设置 GPT-6.1 Sol，保存后刷新，执行合成小记标题并核对数据库运行的 requested_model；最后恢复验收账号原偏好、软删除合成内容。不能用目录返回成功替代实际模型调用。
