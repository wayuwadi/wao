# ADR-142：问问 AI 使用隔离的按需联网检索

## 状态

已接受（2026-08-27）。补充 ADR-041、ADR-046、ADR-098、ADR-128 与 ADR-137；内部只读分析、聊天复盘和天人解读继续禁止联网。

## 上下文

“问问 AI”只能检索 WAO 资产。Runner 在进程参数中固定 `web_search=disabled`，DeepSeek 与受信模型网关也只有 Chat Completions，
因此模型无法核验当前日期、外部事实或最新资料。只改提示词会伪装出不存在的联网能力；抓取无稳定契约的搜索结果页也缺少可靠性与来源边界。

仓库的点子调研已经通过 Codex 顶层 `--search` 验证了隔离联网路径：临时空仓库、独立 `CODEX_HOME`、`read-only` sandbox，
并继续关闭 Shell、多代理、图片和 GitHub 凭据。该成熟路径可以复用，但不能偷偷忽略用户明确选择的 DeepSeek 或模型网关。

## 决策

- 普通“问问 AI”把 `web_search_enabled` 固化到短期运行。使用本地或集中 Codex 能力时，Runner 开放 `--search`，由模型仅在时效性、
  外部事实或 WAO 材料不足时搜索；回答必须附可直接打开的 URL，来源不足或冲突时明确说明。
- 使用 DeepSeek 或固定受信模型网关时采用两阶段编排：兼容 Runner 先以 `ASSISTANT_WEB_SEARCH` 模式生成最多 20,000 字的带 URL 联网材料，
  CAS 转为 `provider_queued` 后，仍由用户选定的 Provider 生成最终答复。搜索阶段使用默认 Codex 账号或 Runner 本机登录态，不把 Provider 凭据下发到 Runner。
- 联网材料是不可信引用，不能覆盖系统指令；Provider 只能引用其中可核对的公开 URL，并区分网页事实、WAO 资产和推断。
  `web_context` 只存在于短期队列，成功、失败或超时进入终态时与完整提示一起清空。
- 页面 Provider 问答只有在脚本哈希匹配的 Codex Runner 在线时才可发送；搜索或 Provider 任一阶段失败都显示真实错误，不静默跳过联网、换模型或回退旧答案。
- 内部 `runReadOnlyJob`、聊天复盘、文档润色和天人解读保持 `web_search_enabled = 0`。它们继续显式传入
  `web_search=disabled`，避免将一次页面体验改动扩成后台资料外传能力。

## 后果

- WAO 资产检索和公网搜索可以在一次问答中互补，最新事实可沿 URL 核对；用户选择模型网关时，最终措辞与推理仍由该网关完成。
- Provider 页面问答新增一次 Codex 搜索阶段，会增加延迟和 Codex 用量，并依赖至少一个兼容 Runner 在线；这是保留真实联网与模型选择语义的代价。
- 现有 assistant-v2 协议只新增模式值，不开放 Shell、文件、图片、多代理、MCP 或业务写入能力；旧 Runner 因脚本哈希不匹配继续 fail-closed。
