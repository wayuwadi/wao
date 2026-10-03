# 本机 Codex 自动更新

只适用于持有已有 ChatGPT 登录的 Windows 服务主机。业务发布不顺带下载 CLI；旧 Runner 继续下线。官方安装来源见 [Codex CLI](https://learn.chatgpt.com/docs/codex/cli)，运行时从官方 npm 注册表发现实际稳定版，不从文档快照硬编码最新版本。

## 首次启用与检查

在有 Node、npm、Python 3.11+ 的主机上，运行（路径按本机安装位置填写）：

```powershell
python deploy/scripts/update-local-codex.py --root "$env:USERPROFILE/.wao/codex-runtime" --npm-cli '<Node安装目录>/node_modules/npm/bin/npm-cli.js' --force
```

脚本不读认证文件、不打印原始模型/账号信息。首次和每个新版本使用固定合成材料调用一次文字、一次图片，不切换个人默认模型。成功后 `current.json` 指向版本；`status.json` 和 `probes/*/report.json` 记录实际版本、可见模型和合成验证结论。原始合成图片按 thread 标识留本机，不提交 Git。

把服务现有 `wao.ai.local-notes.executable` 与启用时的 `wao.codex-quota.executable` 配置成该 `current.json` 的绝对路径。首次切换配置需正常发布/重启，先确认任务空闲；以后版本更新不用重启。原始 CLI 路径配置仍兼容。可用 `start-local-note-ai.ps1 -CodexPath <current.json路径>` 本地验收。

安装每日 06:15 检查（仅当前用户交互登录，最小权限，后台隐藏运行）：

```powershell
./deploy/scripts/install-local-codex-update-task.ps1 -PythonPath '<python.exe>' -NodePath '<node.exe>' -NpmCliPath '<npm-cli.js>'
Get-ScheduledTask -TaskName WAO-Local-Codex-Update
Get-ScheduledTaskInfo -TaskName WAO-Local-Codex-Update
```

脚本副本保存在运行目录，任务不依赖 worktree。`StartWhenAvailable` 补做错过的任务，运行上限二十分钟，重复实例跳过。指针采用同目录原子替换，既有任务的进程不受影响。

## 失败、回退和暂停

- 网络、下载或兼容检查失败：`failed_previous_preserved`；指针不变，十二小时内不重复探测。按 `reason` 排查网络、ChatGPT 登录或缺失的 CLI 能力；必要时 `--force` 重验。
- 手动回退：`python deploy/scripts/update-local-codex.py --root <运行目录> --rollback`。验证旧制品摘要再切换，新请求使用前一版，既有请求不重做。产生 `paused.json`，后续自动检查暂停。
- 首次版本没有 previous：回退命令明确失败；首次切换前保留旧启动器和原 CLI 路径，按既有发布回退流程恢复，不能伪报存在旧版。
- 恢复自动更新：排查完成后移走运行目录下的 `paused.json`，再手动执行一次 `--force`。暂停计划任务也可使用 `Disable-ScheduledTask -TaskName WAO-Local-Codex-Update`。
- 脚本保留版本目录，不自动删除曾用制品；清理前确认没有运行中进程和回退引用。

目录接口每五分钟刷新，设置弹窗每分钟检查更新；新模型出现后可选，原模型选择保持不变。目录或登录失败继续显示已有目录及明确提示，不凭版本号宣称账号拥有某模型。

## 回归与真实验收

```powershell
python -m unittest discover -s deploy/scripts -p test_update_local_codex.py
mvn -pl wao-server -am test '-Dtest=LocalCodexExecutableTest,LocalNoteCodexProcessTest,LocalCodexModelServiceTest,LocalCodexQuotaServiceTest,LocalCodexQuotaProcessTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

隔离测试覆盖候选配图失败不替换、回退暂停、旧版损坏拒绝回退、检查退避、非法路径与摘要损坏拒绝。手工验收需在本地真实 WAO 使用受管理指针完成 AI 操作并刷新读取结果；真实重启、升级时在途请求与未来未知版本不由模拟测试替代。
