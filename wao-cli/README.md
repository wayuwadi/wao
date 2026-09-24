# WAO CLI

`wao` 使用现有工作区 MCP，命令权限与当前令牌的 `tools/list` 完全一致。需要 Node.js 22+。

## 安装与连接

在仓库内执行 `cd wao-cli && npm link`，之后可在终端运行 `wao help`。无需安装第三方依赖。

在 WAO 首页生成一枚**工作区**令牌，点击弹窗中的“复制令牌（用于 CLI）”。Mac 上运行 `wao auth login`，按提示粘贴以 `wao_workspace_` 开头的令牌；CLI 先验证服务端，再将令牌存入 macOS 钥匙串。也可粘贴包含唯一工作区令牌的完整连接说明。自动化脚本或其他系统可设置 `WAO_TOKEN` 环境变量；该变量优先于钥匙串。不要把令牌写进命令参数、Git 或脚本文件。`wao auth status` 可验证连接，`wao auth logout` 仅删除钥匙串中的令牌，不会吊销服务端令牌；吊销请在 WAO 页面完成。

默认连接 `https://wao.wayuwa.cn/api/mcp/workspace`。自建环境可设置 `WAO_URL` 或对单次命令使用 `--url`。远程地址必须是 HTTPS，本机测试允许 HTTP。

## 使用

```bash
wao tools list
wao projects list
wao projects get PROJECT_ID
wao tasks open --limit 10
wao tasks open --mine-only false --project-id PROJECT_ID --output json
wao tasks create --project-id PROJECT_ID --title '准备发布说明'
wao documents get DOCUMENT_ID --output json
wao documents update DOCUMENT_ID --input-file update.json
wao tools call list_open_tasks --input '{"limit":5}'
```

常用子命令覆盖工作区 MCP 当前的 8 个读取工具和 11 个写入工具，详见 `wao help`。参数名直接取自服务端实时工具 schema，例如 `project_id` 对应 `--project-id`。复杂参数使用 `--input-file`，`--input-file -` 从标准输入读取 JSON 对象。工具新增后，可先用 `wao tools list` 和 `wao tools call NAME --input-file payload.json` 调用，再为常用操作增加子命令。

列表默认以表格显示；`--output json` 输出原始业务数据，适合管道和脚本。错误写入标准错误并返回非零退出码。CLI 不保存或重试失败的写入；如遇超时，应先查询 WAO 当前状态，再决定是否重试。

`update_document` 替换正文时，先用 `wao documents get` 读取当前 `rev`，再在输入 JSON 中提供 `expected_rev` 和完整 `blocks`，以便服务端阻止覆盖他人的编辑。

## 开发

运行 `npm test` 验证 HTTP、权限、参数和输出行为。新业务 API 或服务的 CLI 覆盖检查见 [项目 skill](../.codex/skills/wao-cli-sync/SKILL.md) 与 [CLI 规格](../docs/specs/wao-cli.md)。
