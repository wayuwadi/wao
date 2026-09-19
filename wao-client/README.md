# WAO 桌面客户端

Windows 与 macOS 共用的完整 WAO 工作区，使用 Tauri 2 + Vue 3 + TypeScript。业务界面直接复用线上 `wao-web`，桌面外壳在此目录维护；`wao-desktop` 仍是独立桌宠，不互相覆盖安装或更新。

## Windows 本地开发

1. 安装 Node.js 24、Rust stable MSVC、Visual Studio 2022 Build Tools 的「使用 C++ 的桌面开发」及 Windows SDK，安装 WebView2 Evergreen Runtime。
2. IDE 打开本目录，运行 `npm ci` 和 `npm run desktop:dev`。Vue 外壳支持 Vite 热更新，Rust 改动自动重新编译。
3. 默认连接 `https://wao.wayuwa.cn`，使用自己的 WAO 账号登录。客户端与系统浏览器 Cookie 隔离，但客户端重启和覆盖安装保留自身会话。
4. 同时开发业务页面时，在另一个终端启动 `wao-web`，设置 `$env:WAO_WEB_DEV_URL='http://localhost:5173'` 后启动客户端。只有 debug 构建接受 loopback URL；发布包始终连接生产 HTTPS。

```powershell
npm ci
npm test
npm run build
npm run desktop:dev
```

前端入口：`src/App.vue`；原生窗口与导航边界：`src-tauri/src/lib.rs`；打包配置：`src-tauri/tauri.conf.json`；更新状态：`src/updater.ts`。

`npm run dev` 只预览外壳，无业务 WebView、登录或原生操作，不属于客户端验收。

若 Windows Code Integrity 明确拒绝加载 Rust DLL，本地前端仍可开发；安装包交由 Linux2 的 `wao-client-release` 交叉构建。不要关闭或绕过企业安全策略；本机原生编译须由设备管理员批准开发工具链。

旧的 GitHub Actions 开发宿主制品仍可用于本地调试；新的 Jenkins 任务只构建正式安装包。修改 Rust 原生逻辑后需本地重新编译开发宿主，不再用 `client-check-*` 标签生成。

开发宿主使用独立应用标识与会话目录，不与已安装正式版抢占单实例；开发界面不会下载、安装生产更新。开发时仍使用真实 WAO 后端，请勿把测试业务写入误认为演示数据。

启动脚本会把下载的宿主复制为 `dev-host/runtime/wao-client-dev.exe` 再运行，避免正式安装器按 `wao-client.exe` 进程名关闭开发宿主。更新宿主制品后先退出旧宿主再重新执行启动命令；Vue/TypeScript/CSS 热更新无需替换宿主。通过 `desktop:dev` 自行编译的原生调试进程仍应在安装正式版前保存并退出。

## 打包与迭代

```powershell
npm run desktop:build
```

Windows 安装包位于 `src-tauri/target/release/bundle/nsis/`；Mac 通用安装包位于 `src-tauri/target/universal-apple-darwin/release/bundle/dmg/`。本地没有更新私钥时只生成安装包，不生成可发布更新签名；Jenkins 在 Linux2 上统一签名并用旧客户端公钥验证。无需把私钥复制到开发机。

普通 Web 功能由既有 Jenkins 发布；客户端主动刷新或重新启动后使用最新业务页面。不会强制刷新正在编辑的工作区。

外壳更新的标准流程：

1. `npm run version:set -- 1.0.1`，执行 `npm install --package-lock-only`，原生检查同步 `Cargo.lock`，完成测试后按一个需求一个 PR 合并。
2. 在自建 Jenkins 的 `wao-client-release` 选择 `PUBLISH_CLIENT=true`、`BUILD_MACOS=true`。Linux2 构建 Windows NSIS，Mac 节点构建 Universal 包；双端齐全后由 Linux2 使用原有私钥签名、验签、原子发布并核对公网文件哈希。
3. 客户端进入「客户端 → 检查更新」，下载并验证后点击「安装并重新打开」。安装前保存编辑内容；安装标识保持不变，覆盖升级保留会话。

影子验收选择 `PUBLISH_CLIENT=false`；没有 Mac 节点时可关闭 `BUILD_MACOS` 仅验收 Windows。正式发布只允许 main，必须双端齐全且版本高于线上。原生安装与覆盖升级需要实机验收，不能用交叉编译代替。`client-v*` 与 `client-check-*` 不再触发 GitHub Actions。配置、节点与密钥准备见 `deploy/jenkins/client/README.md`。

- 固定下载地址：`https://wao.wayuwa.cn/downloads/desktop/client/WAO-windows-x64-setup.exe`
- Mac 固定下载：`https://wao.wayuwa.cn/downloads/desktop/client/WAO-macos-universal.dmg`
- 公开下载页：`https://wao.wayuwa.cn/download`
- 更新清单：`https://wao.wayuwa.cn/downloads/desktop/client/latest.json`
- 版本安装包：`https://wao.wayuwa.cn/downloads/desktop/client/WAO_<version>_windows-x64-setup.exe`
- 版本 SHA256：`https://wao.wayuwa.cn/downloads/desktop/client/SHA256-<version>.txt`

这些是发布目标地址，实际可用版本以已成功完成的 Jenkins 发布与线上清单为准。原有更新私钥保存在 Linux2 受限文件中；云发布使用 `wao-cloud-ssh` 和 `wao-cloud-known-hosts` 凭据，Secret 值不得入库。

更新签名用于验证 WAO 更新来源，不能代替 Windows Authenticode 代码签名。没有组织代码签名证书时不宣称安装包已受 Windows 信任；需要证书的受管设备由管理员处理。

## macOS 开发与安装

支持 macOS 11.3 及以上，通用 DMG 同时包含 Intel x86_64 与 Apple Silicon arm64；共享 Windows 的全部工作区、会话和更新 UI，使用系统标题栏与编辑快捷键。关闭窗口保留工作区，从 Dock 或菜单栏恢复；Cmd+Q 和菜单退出会提示保存，Cmd+W 关闭当前工作区页签。

macOS 的工作区与宠物使用 AppKit 保存屏幕位置和窗口尺寸，避免跨缩放屏幕重启后尺寸减半。首次采用该机制会使用默认布局，此后恢复原生保存的位置；正式版与开发宿主的记录相互隔离。

安装 Xcode Command Line Tools、Node.js 24 和 Rust，进入本目录：

```bash
npm ci
npm run desktop:dev
# 通用安装包需要两个目标
rustup target add aarch64-apple-darwin x86_64-apple-darwin
npm run desktop:build -- --target universal-apple-darwin
```

正式构建使用 `wao-client-release` 的 `wao-client-macos` 节点；生成 DMG 与 `.app.tar.gz`，使用 `lipo` 校验两个架构并验证应用代码签名，更新签名由 Linux2 统一生成。`prepare-release.mjs` 要求 Windows 安装包、Mac DMG、Mac 更新归档及两端更新签名齐全；公开校验重新下载所有文件并比较 SHA-256。稳定下载文件和清单不缓存，版本文件不可覆盖。

打开 DMG，将 WAO 拖入 Applications 后从应用程序启动。当前仅 ad-hoc 代码签名，没有 Apple Developer ID 签名与公证；首次打开如遇系统拦截，用户确认来源后可在“系统设置 → 隐私与安全性”处理该应用。不要关闭系统整体安全保护。应用内更新使用已有 WAO 更新公钥强制校验，不需卸载旧版。

## 验收记录

验收结果写入 `docs/runbooks/desktop-client-v1-acceptance.md`。未验证的项目保留明确状态，不用界面预览或模拟接口代替实机与生产验收。


Windows 托盘右键菜单提供“新消息闪烁”，默认开启。工作区有未读聊天或通知时闪烁，清零或关闭后恢复；开关保存在本机应用配置目录的 `tray-flash.json`，重启保留。读取失败使用默认值，保存失败在菜单中显示重试提示。macOS 不启用闪烁。
