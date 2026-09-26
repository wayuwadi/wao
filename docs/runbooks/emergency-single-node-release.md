# WAO 单节点应急发布

仅在节点机器或管理通道故障、构建代码本身已通过验证时使用。正常发布继续由 Jenkins 双节点滚动完成。一次只选择一个路径，发布中不要并发触发 Jenkins 与 GitHub Actions。

## Jenkins 仍可用

- Linux2 不可用而 Linux1 健康：手动运行 `wao-main-cd`，选择目标 `main`，设置 `ALLOW_MASTER_OFFLINE=true`，保持 `BOOTSTRAP_SLAVE=false`、`ALLOW_SLAVE_OFFLINE=false`。若 Linux2 是安装后失败，流水线会在 Linux1 同 SHA 已通过安装和云端就绪验证后继续；Linux1 失败则停止。
- Linux1 SSH/Docker 不可用而 Linux2 健康：设置 `ALLOW_SLAVE_OFFLINE=true`。Linux1 将保持摘流，Linux2 原地更新可能短暂中断。
- 验收 Jenkins 单节点状态、公共 `/api/edge/ready`、`/version.json` 与首页 `data-v` 的 SHA。飞书卡片应写明单节点，不显示双节点成功。

## Jenkins 不可用，使用 Linux1 控制台

1. 在 Linux1 准备**独立于** `~/deploy/wao` 的私有仓库 checkout。使用既有 GitHub 访问方式获取最新 `origin/main`；不要发送或写入密码、Token、私钥到命令或日志。确认 `git status --short` 为空，`git rev-parse HEAD` 与 `git rev-parse origin/main` 都是目标完整 SHA。
2. 在 checkout 根目录运行 `bash deploy/scripts/emergency-release-linux1.sh <完整 SHA>`。脚本会在 Linux1 用 Docker 运行后端与前端测试、前端类型检查和镜像构建，再复用现有私有环境文件调用安装器，验证三项应用镜像、容器健康和数据库就绪。只有打印 `WAO_LINUX1_EMERGENCY_RELEASE_VERIFIED=<SHA>` 才进入下一步。安装器会保存应用原镜像用于故障回滚，不清理线上数据库。
3. 在 GitHub Actions 手动运行 `Emergency Linux1 browser release`，输入上一步的完整 SHA。托管 runner 会重新确认它就是当前 `main`，构建两个网页，使用既有云端 SSH 身份上传不可变静态制品，并在云端检查 Linux1 直接就绪后切换入口，摘流尚未同步该版本的 Linux2。
4. 仅当工作流打印 `WAO_EMERGENCY_PUBLIC_RELEASE_VERIFIED=<SHA>`，且浏览器打开 WAO、`/api/edge/ready` 成功、公开版本对应目标 SHA 时，报告新版本可用。Linux2 恢复后重新发布同一 `main` SHA 到两节点并检查版本一致。

如果 Linux1 没有可读取私有仓库的既有访问方式，需要先由用户在 Linux1 控制台完成安全登录；不要把私有凭据复制到本机或对话。若云端 SSH 密钥的权限或已安装 Nginx 路由与预期不符，工作流会失败并保留旧静态页；先修复具体环境问题再重试。
