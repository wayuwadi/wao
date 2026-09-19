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
