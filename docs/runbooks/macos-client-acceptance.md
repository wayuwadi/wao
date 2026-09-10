# WAO Mac 客户端 1.0.2 验收

日期：2026-09-10。承接用户已验收的 Windows 1.0.0，交付同一完整工作区的 Mac 通用版（Intel 与 Apple Silicon，macOS 11.3 及以上）。Windows 的既有验收记录保留在 `desktop-client-v1-acceptance.md`。

## 自动检查

- Mac 本机（Intel，macOS 15.7.5）：客户端前端 15 项测试通过，Rust 原生测试 3 项通过；外壳类型检查和生产构建通过。
- 下载页 8 项测试通过，涵盖两架构归档一致、DMG 元数据、HTML 拒绝和 Windows 失败时 Mac 下载独立可用。
- Nginx 下载配置测试在既有 Linux2 的临时目录执行通过，不修改运行配置；固定 Mac DMG 入口禁止缓存。
- Linux 发布测试已在正式发布 job 通过，覆盖同版本重试、版本文件变更拒绝与清单降级拒绝。
- 正式 CI DMG 已只读挂载检查：应用版本 `1.0.2`、最低系统 `11.3`、`x86_64 arm64` 双架构、代码签名、`Applications` 安装入口与磁盘映像完整性均通过；检查后已卸载。
- Mac 更新归档解包后的应用版本、双架构和代码签名检查通过。Windows 与 Mac 更新包均用客户端公钥独立校验签名及可信注释，篡改包的校验失败。

## 实机和交付边界

本次会话原生应用 UI 自动化不可用，浏览器控制也发生超时。自动检查、二进制架构和代码签名校验不能替代真实登录后的 UI 操作。以下需 Mac 用户确认或后续可用的原生 UI 验收：

- 邮箱/飞书登录及重启后会话保留；文件上传、下载、外链和新窗口。
- 原生标题栏、业务 WebView 的缩放与布局；编辑快捷键。
- 未保存草稿切换更新中心、关闭与 Dock 恢复后保留。
- 实际应用内下载、签名验证、确认安装和重新打开。
- Apple Silicon 实机启动和业务使用（Universal 架构检查不能代替此项）。

当前没有 Apple Developer ID 证书和公证；应用使用 ad-hoc 代码签名，公开下载页如实说明。Tauri 更新签名独立校验，私钥仅在 CI Secret 中使用。

## 发布记录

- 功能 PR #668、CI 架构校验修复 PR #669、Mac 文件下载修复 PR #670 均已合并。正式标签 `client-v1.0.2` 对应 `8b9e1a35c375aea4716f9ebc94e6f2ae7aa4614e`。
- [正式发布工作流 34436931251](https://github.com/wayuwacn/wao/actions/runs/34436931251) 的 Windows、macOS、publish 三个 job 均成功。
- [Jenkins #328](https://wao.wayuwa.cn/jenkins/job/wao-main-cd/328/) 部署成功，线上主站版本 `8b9e1a3`；公开下载页脚本已包含 Mac 入口、macOS 11.3 和安装说明。
- [官网下载页](https://wao.wayuwa.cn/download#desktop) 的实际下载 API 已对公开清单和安装包 HEAD 验证通过，Windows 与 Mac 均返回 `1.0.2`。
- [Mac 1.0.2 DMG](https://wao.wayuwa.cn/downloads/desktop/client/WAO_1.0.2_macos-universal.dmg)：9,985,557 字节，SHA-256 `a018b571d8eb862bf261698e9ea2d3f5751392c1eff2b58bcc26b3b55e1c2be5`。
- 正式清单版本、两种 Mac 架构的更新地址和签名、全部 7 个公开安装/更新文件的 SHA-256，以及公开 `SHA256-1.0.2.txt` 均与 CI 产物比对通过。
- 1.0.1 仅保留为本机预览历史，其发布在修复 WKWebView 文件下载问题前主动取消；不作为正式公开版本。
