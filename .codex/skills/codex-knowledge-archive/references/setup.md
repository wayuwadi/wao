# 安装与维护

## 每台电脑首次安装

把 Skill 包解压至 `~/.codex/skills/codex-knowledge-archive`；自定义 `CODEX_HOME` 时放入它的 `skills`。Windows 默认 `%USERPROFILE%\.codex\skills\codex-knowledge-archive`。重新打开 Codex 任务后调用 `$codex-knowledge-archive`。Python 3.9 或更新版本，不需要第三方包。

在 WAO 记忆中心创建一枚设备令牌并启用该设备的“Codex 工作记录归档”。知识库中的聊天与工具过程遵循团队现有知识库可见范围，安装前确认工作目录排除需求。

macOS / Linux：

```bash
python3 ~/.codex/skills/codex-knowledge-archive/scripts/archive.py configure --history all
python3 ~/.codex/skills/codex-knowledge-archive/scripts/archive.py sync
python3 ~/.codex/skills/codex-knowledge-archive/scripts/archive.py status
python3 ~/.codex/skills/codex-knowledge-archive/scripts/schedule.py install
```

Windows PowerShell（使用 `py -3` 启动时，计划任务仍固定到它实际选择的 Python 解释器）：

```powershell
py -3 "$HOME\.codex\skills\codex-knowledge-archive\scripts\archive.py" configure --history all
py -3 "$HOME\.codex\skills\codex-knowledge-archive\scripts\archive.py" sync
py -3 "$HOME\.codex\skills\codex-knowledge-archive\scripts\archive.py" status
py -3 "$HOME\.codex\skills\codex-knowledge-archive\scripts\schedule.py" install
```

`configure` 无回显索取令牌；不要将实际密钥加入命令历史。已有仅当前用户可读的令牌文件可以传 `--token-file`，脚本不会复制或打印其内容。Windows 请用用户目录 ACL 限制令牌与状态目录，不放在公共共享目录。

## 配置

私有状态默认 `~/.wao-codex-archive`，可用 `WAO_CODEX_ARCHIVE_HOME` 环境变量或 `archive.py --state <目录>` 指定（参数位于子命令前）；计划安装器读取同名环境变量并把路径固化。`config.json` 含服务器、令牌文件路径、随机 deviceId、账号 id、Codex 目录、排除规则与暂停标志。不把该目录放入 Git、同步到另一台设备或打包发布。

- 首次 `configure --history new` 只采集配置时间之后新增的消息，旧会话中新增的消息也会收集。
- `--exclude '/工作目录/私密项目*'` 可重复传入，以完整工作目录 glob 匹配。
- 配置完成后，先 pause，再编辑 config.json 的 `excludeProjects` 或设备显示名，随后 resume。已经排入 outbox 的内容仍会上传；要撤回待发送内容时保持暂停，人工核对队列，不能仅修改排除规则就声称已撤回。
- 切换账号 / 目的地必须用新状态目录；不复用旧回执和待发数据。更换本账号令牌可更换 token 文件内容，保持 deviceId 不变。
- 原始日志留在 Codex，本采集器不清理它们；待发正文存在 SQLite 中，成功后只留下回执。导出文件需由用户自行管理。

## 查看系统计划

- macOS：`launchctl print gui/$(id -u)/cn.wayuwa.codex-archive`。日志在状态目录 scheduler.log / scheduler-error.log；是操作摘要，不含聊天正文。
- Linux：`systemctl --user status cn.wayuwa.codex-archive.timer`，执行结果 `journalctl --user -u cn.wayuwa.codex-archive.service`。不自动启用 linger。
- Windows：`schtasks /Query /TN cn.wayuwa.codex-archive /V /FO LIST`。当前用户已登录时执行，任务使用最低权限。

`schedule.py remove` 只移除本 Skill 的同名系统计划，不删除已上传知识与断点。安装器拒绝覆盖已有同名计划；重装时先移除旧计划。休眠期间不执行，恢复后继续。

## 格式与兼容性

JSONL 适配器基于 Codex `session_meta` + `response_item`，保存文本与工具记录；忽略重复的 event_msg，不含隐藏推理。来源保持 sessionId / project / timestamp / role；来源偏移与 part 在服务器决定幂等文档身份。解析失败检查 status 的文件名，升级解析器后重试，不删除 state.sqlite。

官方说明指出 transcript 格式可能变化：[OpenAI Hooks 文档](https://developers.openai.com/codex/hooks)。本 Skill 用操作系统计划处理历史与增量，不依赖某个客户端必须支持 hooks。仅安装了 Python 脚本的设备能采集其本机记录，不能读取其他用户的 Codex 账号或云端历史。
