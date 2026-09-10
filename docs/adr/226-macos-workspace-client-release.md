# ADR-226：Mac 工作区客户端与双平台发布

- 日期：2026-09-10
- 状态：已采纳

承接 ADR-222 的 Windows 正式版，本次交付同一 `wao-client` 的 macOS 客户端。使用系统标题栏、原生编辑菜单与快捷键；关闭保留工作区，Dock 和菜单栏可恢复，退出先提示保存。业务 WebView 和登录原点、安装标识、更新公钥均保持稳定。

客户端从 1.0.1 起一次构建 Windows x64 与 macOS Universal（Intel / Apple Silicon，macOS 11+）。两端构建成功且签名齐全后才发布统一 `latest.json`，两个 darwin 平台指向同一 Universal 更新归档。DMG 供首次安装，`.app.tar.gz` 供应用内签名更新，二者不可混用。

发布先验证全部文件 SHA-256 与已有版本的不可变性，再原子替换文件，最后替换清单；保留既有 Windows 下载与更新通道。公开 `/download` 独立检查每个平台，Mac DMG 通过清单和二进制响应校验后才显示可下载；补充拖入 Applications 和首次打开说明。

现有环境没有 Apple Developer ID 证书，Mac 使用 ad-hoc 代码签名并如实披露未公证状态，应用内更新仍强制校验现有 WAO 签名。不得把自动检查、Intel 本机启动或 Windows 验收扩展为 Apple Silicon 实机、飞书登录及全部业务验收；实测边界记录在验收文档。
