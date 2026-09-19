# ADR-236：桌面工作区客户端迁移至自建 Jenkins

- 日期：2026-09-19
- 状态：已采纳；节点与发布验收以运行记录为准

## 背景

下载站实际仍发布 1.1.5，源码为 1.1.9。GitHub Actions 在执行任何构建步骤前因账户付款或额度限制拒绝启动 Windows 和 Mac 作业。用户要求在 Linux2 搭建自有流水线，后续客户端构建不再使用 GitHub Actions。

## 决策

- 用 Linux2 既有 Jenkins 的独立任务 `wao-client-release` 替代 `.github/workflows/client-release.yml`，代码仍托管在 GitHub。首期使用参数化手动触发；默认只构建，明确勾选 `PUBLISH_CLIENT` 才发布，避免每个 Web 提交都重复生成原生安装包。
- Windows 使用固定 Rust、Node、cargo-xwin 工具链容器，在 Linux2 构建 x64 NSIS；Rust Windows 测试只编译，不把交叉编译当作 Windows 实机运行。限制编译为 4 核、6 GiB，与主站发布共享受控执行器。
- Mac 使用 `wao-client-macos` 标签的自有 macOS 节点，构建 Universal DMG 与应用更新归档，执行原生测试、双架构和应用签名检查。Linux2 不能替代 Mac 原生工具链。
- 取代 ADR-226 中通过 GitHub Actions 双端构建的执行方式。保留双端齐全、不可变版本、禁止降级、文件先上传和清单最后原子替换的发布契约。Windows 单端构建只归档 Jenkins 制品，不替换下载站。
- 取代 ADR-096 中私钥仅存 GitHub Secret 的客户端使用方式：原有更新签名私钥需恢复到 Linux2 的 `/etc/jenkins/secrets/tauri-updater.key`，限制为 `root:jenkins 0640`。私钥不进入 Git、Mac 节点、制品或日志。沿用已安装客户端的公钥，不能因缺少旧密钥而替换公钥。
- 两端无更新签名的构建产物在 Linux2 上统一签名，再使用客户端已有公钥进行真实验签。云端 SSH 凭据沿用既有 Jenkins 凭据，严格检查主机指纹。
- 仓库版本、npm 和 Cargo 锁文件必须一致；正式发布只允许 main 且版本严格高于线上。整个作业禁止并发，120 分钟超时；Mac 排队和构建合计最多 45 分钟，缺节点不会永久挂起。

## 验收边界

版本门禁和签名篡改测试、流水线解析、Linux2 实际影子构建分别记录。缺少原有私钥或 Mac 节点时不得宣布正式发布成功。Windows 实机安装、Mac 覆盖升级和更新后业务体验另行记录，不由单元测试替代。

官方依据：[Tauri Windows NSIS 交叉构建](https://v2.tauri.app/distribute/windows-installer/)、[Jenkins 构建节点](https://www.jenkins.io/doc/book/using/using-agents/)。
