# 个人 Agent 记忆

WAO 设置 → 个人与账号 → Agent 记忆提供候选审核、已确认记忆、版本历史和归档。Codex 和 Agy 可以接续同一份已确认的经验。项目标识用仓库名或组织/仓库名，统一按小写匹配，留空表示通用。这是个人记录的适用范围。

## 日常使用

1. 在能力中心新建「仅 Agent 记忆」的 CLI 连接。按连接提示执行 `wao memories authorize`，把专用令牌保存在 macOS 钥匙串。同一 WAO 地址的记忆连接使用独立凭据条目。CLI 可从仓库 `wao-cli` 目录用 `npm link` 安装。
2. Agent 遇到明确纠正、反复出现的习惯或已验证的可复用做法时，可提交一条候选。用户在 WAO 核对内容、来源和适用范围后确认，也可编辑后合并到同范围的已有记忆。记忆归提交设备对应的成员，其他成员无法访问。
3. 已确认的通用偏好可以勾选恢复至全局规则。项目经验由 Agent 按需检索，分支、部署和在途状态仍需实时核实。凭据、原始聊天和未经授权的业务数据应留在各自管理渠道。

## 换到新 Mac

安装 Codex、Agy 和新版本 WAO CLI，重新生成本机令牌。部署地址举例：

```sh
wao memories authorize --url https://wao.wayuwa.cn/api/mcp/workspace
wao memories restore --url https://wao.wayuwa.cn/api/mcp/workspace
wao memories restore --url https://wao.wayuwa.cn/api/mcp/workspace --apply
```

默认预览 Codex、Agy 两端，也可用 `--target codex` 或 `--target agy` 只处理一端。Codex 写入用户 Skill 和当前生效的全局规则区块。Agy 安装同名全局 Skill 与独立 WAO 规则文件。打开新的 Agent 会话后验证发现与行为。恢复不需要旧电脑或旧 Codex 记忆目录。刷新偏好时重新执行预览和安装。撤销上一次安装可用 `wao memories restore --undo`，只还原本次的 WAO 区块和文件；如果管理内容被手动编辑，先处理 CLI 报出的冲突。主动吊销设备会停止在线查询和提交；本机已恢复的文字由设备本人清理。

## 导入 Codex → Agy 迁移手记

对迁移 Skill 的目录先运行 `wao memories import --directory SKILL_DIR`。命令列出 `references/memory-index.md` 的章节和 `references/ad-hoc-notes` 下的手记。用一个或多个 `--entry ID` 选中条目并预览，可带 `--project` 和 `--kind`；用同样的参数加 `--apply 预览编号` 后才提交。所有条目都进入待确认，批次若中途失败，可用同一预览编号重试。含旧机 Codex 归档指针的行会排除，实际可复用正文请在预览中核对。首批内容由用户选择。

## 接口与版本

- Web 会话走 `GET/POST /api/agent-memories`、`GET /api/agent-memories/{id}/history` 和 `POST /api/agent-memories/{id}/review`。合并同时验证候选与目标两个版本，其余操作验证当前版本。历史内容保持原样。
- 工作区连接仅新增 `search_agent_memories` 和 `propose_agent_memory` 两个显式授权工具。读取仅返回已确认记录，按项目键与通用范围检索；提交只返回记录标识和状态。Web 用户才可确认、合并、驳回和归档。
- V217 为统一记忆版本添加 Agent 来源、项目标识和启动偏好的关联表。历史数据无需补填。若回退应用，保留 V217 表及已存内容，使用现有发布协调器回退制品。
