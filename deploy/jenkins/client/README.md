# 桌面工作区客户端构建与发布

任务：`wao-client-release`；配置：`Jenkinsfile.client`。GitHub 只提供代码托管，不运行客户端 Actions。

## Linux2

沿用 Jenkins 的 Docker、Git SCM 与云发布 SSH 凭据；先以 Jenkins 用户运行 `bash deploy/jenkins/client/configure-git.sh` 配置 GitHub 官方 SSH 443 入口，继续使用已核验的 github.com 主机公钥；应用 JCasC 后出现独立任务。首次先选 `PUBLISH_CLIENT=false`、`BUILD_MACOS=false` 验收 Windows 交叉构建。构建镜像和依赖放在 Linux2，缓存位于 Jenkins 用户的 `caches/wao-client-windows`；控制器重启后仍保留。

构建生成 NSIS 安装程序并归档为 Jenkins 制品。交叉构建编译 Windows 测试，但不执行 Windows 原生测试，交付记录必须保留此限制。没有 Mac 或私钥时仍可做 Windows 影子构建。

## Mac 节点

使用有 Xcode Command Line Tools、Node 24.15+、Rust stable、Jenkins 所需 Java 版本的自有 Mac，注册唯一标签 `wao-client-macos`。节点连接方式与常驻策略由设备所有者确认；不默认在开发者电脑安装后台服务。原生构建进程需能访问已登录桌面，以完成 DMG 生成；先验证 `xcodebuild`、`hdiutil` 和双架构工具链。

节点只接收同一提交的客户端源码，运行测试、Universal 构建、`lipo` 和 `codesign` 验证，传回 DMG 和 `.app.tar.gz`。不向 Mac 下发更新私钥或云部署凭据。跨用户使用的公共 agent 不适合作为此可信发布节点。

## 签名与发布

由管理员把**原有** Tauri 更新私钥备份放入 `/etc/jenkins/secrets/tauri-updater.key`，所有者 `root:jenkins`、权限 `0640`；当前沿用原流水线的无密码密钥。不要在聊天、命令参数或日志粘贴私钥，不能从 GitHub Secret 读取回原值。没有原密钥时保留现有发布版本，先恢复备份。

合并版本改动到 main 后手动运行，勾选 `PUBLISH_CLIENT=true` 和 `BUILD_MACOS=true`。流水线检查所有版本文件一致及线上版本，双端完成后在 Linux2 签名，使用旧公钥验签，再复用 `wao-client/scripts/publish.sh` 的不可变文件和原子清单切换机制。最终重新下载全部公开文件比较 SHA-256。失败时查看具体阶段；上传前失败不会切换现有清单，上传后公开核对失败需人工检查通道，不能伪称没有外部变更。

GitHub 的旧客户端 workflow 文件已移除；已有 `client-v*` / `client-check-*` 标签保留历史意义，不再触发构建。移动端和独立旧桌宠为其他工程，不在本次完整工作区客户端迁移范围。

## 2026-09-24 迁移与打包修复

Mac 构建必须指定 `--bundles app,dmg`。只选 `dmg` 时 Tauri 会清理临时 `.app`，导致后续
`lipo` 校验及更新归档找不到应用；同时输出两种 bundle 后再做双架构、签名和版本校验。

原签名私钥若只剩 GitHub Secret，使用 `client-signing-key-migration.yml` 完成一次性迁移：
仅在 Linux2 以已有维护用户启动单次、无默认标签的 GitHub runner，唯一标签为
`wao-key-migration-linux2-20260924`。手动从 main 触发后，Secret 通过 GitHub 的加密连接直接传到
Linux2 临时文件，再安装到既定受限路径；不 checkout 代码、不上传制品、不打印密钥、不经 Mac。
runner 使用 `--ephemeral --disableupdate --no-default-labels`，完成后确认注销并删除临时注册凭据。
已有不同私钥时停止，不覆盖。正式签名阶段仍必须用已安装客户端的旧公钥验证签名。
此作业只恢复既有凭据，不恢复 GitHub Actions 客户端构建；平时没有匹配执行节点，不能自动运行。

Mac 节点 `wao-client-macos-wadi` 为当前用户桌面会话的专用入站节点，标签 `wao-client-macos`、
一个 executor、exclusive 模式。通过已核验主机身份的 SSH loopback 隧道和 WebSocket 连接；
节点 Secret 保存在本机用户私有目录，未安装开机常驻服务。正式构建前确认节点在线。

Windows 交叉构建镜像同时安装 `pkg-config` 与 `libayatana-appindicator3-dev`：当前 Linux Tauri CLI
在收集打包元数据时，即使目标为 Windows 仍查询宿主托盘库。仅有 NSIS、Clang 和 Windows SDK
会在可执行文件编译成功后触发 `Can't detect any appindicator library`。构建镜像阶段验证
`ayatana-appindicator3-0.1` 可由 pkg-config 解析，再用真实 Windows NSIS 打包验收。

Linux2 的 Jenkins 非登录服务不会继承维护用户的代理变量。Windows 构建脚本优先沿用已设置的
HTTP(S) 代理，否则使用既有 `127.0.0.1:7897` 出口，并把代理传入构建镜像及运行容器；这修复
NSIS 工具从 GitHub 下载时的 TLS EOF，也避免 Rust 依赖绕过既有出口。迁移构建主机时需显式
设置该主机的 `HTTP_PROXY` / `HTTPS_PROXY`，不要关闭 TLS 验证来绕过网络错误。

正式发布用 Git 对象校验 `HEAD` 与此次 SCM 拉取的 `refs/remotes/origin/main` 完全一致，
不依赖 Jenkins 插件可能返回完整 ref 或未注入的 `GIT_BRANCH` 字符串。缺少 main ref 或
检出其他提交均拒绝发布。客户端不需要历史变更范围，SCM 只浅克隆 main 的一个提交，
避免清理工作区后重新下载全部历史。
