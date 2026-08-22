# ADR-096：以 Tauri 2 交付 Windows/macOS 桌面宠物与签名增量更新

- 状态：已接受
- 日期：2026-08-22

## 背景

ADR-075 曾使用 WPF 实现 Windows 原生客户端与桌面宠物，ADR-077 因完整复制 Web 页面、登录、状态管理和发布链路而将其退役。
现在的目标不是恢复第二套 WAO 客户端，而是给少量自有 Windows 和 macOS 电脑提供一个长期驻留的轻量桌面宠物：首次从 WAO Web 下载，之后直接在宠物上完成更新。

桌面更新包属于可执行代码。仅校验 HTTPS 或 SHA-256 无法证明更新来自 WAO，且 Tauri 2 的更新器强制校验签名。仓库当前没有 Windows Authenticode 或 Apple Developer ID 证书，因此首次安装仍可能触发 SmartScreen 或 Gatekeeper 提示，不能伪装成受操作系统信任的已签名应用。

## 决策

- 新增 `wao-desktop/`，使用 Tauri 2、Vue 3 和 TypeScript 实现透明、无边框、置顶、可拖动的独立桌面宠物，同时构建 Windows x64 与 macOS Universal 安装包。
- 桌宠只提供陪伴状态、WAO 快捷入口、托盘控制和更新，不复制项目、任务、知识库等 Web 页面；“打开 WAO”始终交给系统浏览器。
- 新增公开 `/desktop` 下载页，并把它加入 WAO 的默认快捷链接。页面自动推荐当前系统，同时保留 Windows 与 macOS 的明确手动下载入口。
- 桌宠启动后延迟检查更新，用户也可在宠物卡片或系统托盘中手动检查。发现新版后显示版本和发布说明，由用户确认后下载、安装并重启；失败保留当前版本并可重试。
- 更新使用 Tauri updater 的独立签名密钥。公钥写入客户端，私钥只保存为 GitHub Actions Secret `TAURI_SIGNING_PRIVATE_KEY`，不进入仓库、安装包日志或云节点。
- 安装包、版本化更新包和 `latest.json` 发布到同域 `/downloads/desktop/`。版本化文件长期缓存，`latest.json` 禁止缓存；发布顺序保证清单最后替换，客户端不会看见尚未上传完整的版本。
- 桌面端使用独立手动发布流水线：输入 SemVer 后分别在 Windows 与 macOS Runner 构建，再由发布任务上传到现有云 Nginx。普通 Web/服务端发布不重复消耗桌面构建时间。

## 影响

- Windows 与 macOS 共享同一套交互和更新逻辑，平台差异只留在原生窗口、托盘和安装包构建层。
- 首次安装体积与长期维护面显著小于 Electron；业务功能继续集中在 WAO Web，避免重现 ADR-077 的双客户端成本。
- 更新签名保护“已安装后的升级来源”，但不替代 Windows Authenticode 和 Apple Developer ID 公证。取得对应证书后应在流水线中补充操作系统级签名与 notarization。
- macOS Universal 包同时覆盖 Intel 与 Apple Silicon；Windows 首期只覆盖现有电脑使用的 x64，若出现 Windows ARM 设备再增加目标。
