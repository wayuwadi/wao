# WAO 桌面客户端

Windows 优先的完整 WAO 工作区，使用 Tauri 2 + Vue 3 + TypeScript。业务界面直接复用线上 `wao-web`，桌面外壳在此目录维护；`wao-desktop` 仍是独立桌宠，不互相覆盖安装或更新。

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

若 Windows Code Integrity 明确拒绝加载 Rust DLL，本地前端仍可开发，原生编译由 `client-check-*` 标签触发 GitHub Windows runner。不要关闭或绕过企业安全策略；本机原生编译须由设备管理员批准开发工具链。

还可使用 CI 构建好的开发宿主：从同次工作流下载 `WAO-client-dev-host-windows`，把 `wao-client.exe` 放进本目录的 `dev-host/`（已忽略，不入库），运行 `npm run desktop:dev:host`。它启动 Vite 与已编译的真实 Windows 外壳，修改 Vue/TypeScript/CSS 即可热更新，无需本机运行 Rust 编译器。修改 Rust 原生逻辑时仍需重新构建宿主。宿主只用于开发，不是安装版，仍受本机应用执行策略约束；设备拒绝执行时须由管理员批准。

开发宿主使用独立应用标识与会话目录，不与已安装正式版抢占单实例；开发界面不会下载、安装生产更新。开发时仍使用真实 WAO 后端，请勿把测试业务写入误认为演示数据。

## 打包与迭代

```powershell
npm run desktop:build
```

安装包位于 `src-tauri/target/release/bundle/nsis/`。本地没有更新私钥时只生成安装包，不生成可发布更新签名；CI 中由 Secret 自动签名。无需把私钥复制到开发机。

普通 Web 功能由既有 Jenkins 发布；客户端主动刷新或重新启动后使用最新业务页面。不会强制刷新正在编辑的工作区。

外壳更新的标准流程：

1. `npm run version:set -- 1.0.1`，执行 `npm install --package-lock-only`，原生检查同步 `Cargo.lock`，完成测试后按一个需求一个 PR 合并。
2. 在包含该改动的 main 提交创建 `client-v1.0.1` 标签并推送。GitHub Actions 自动测试、构建、签名、上传和验证生产安装包。
3. 客户端进入「客户端 → 检查更新」，下载并验证后点击「安装并重新打开」。安装前保存编辑内容；安装标识保持不变，覆盖升级保留会话。

分支验证可使用唯一 `client-check-<时间或提交号>` 标签，只产生 CI 制品，不发布生产。工作流也支持手动运行；仅 main 可勾选 publish，版本不可覆盖。制品和下载渠道独立于 Web dist 生命周期。

- 固定下载地址：`https://wao.wayuwa.cn/downloads/desktop/client/WAO-windows-x64-setup.exe`
- 更新清单：`https://wao.wayuwa.cn/downloads/desktop/client/latest.json`
- 版本安装包：`https://wao.wayuwa.cn/downloads/desktop/client/WAO_<version>_windows-x64-setup.exe`
- 版本 SHA256：`https://wao.wayuwa.cn/downloads/desktop/client/SHA256-<version>.txt`

这些是发布目标地址，实际可用版本以已成功完成的工作流与线上清单为准。当前流水线复用 `TAURI_SIGNING_PRIVATE_KEY`、`DEPLOY_SSH_PRIVATE_KEY`、`DEPLOY_SSH_KNOWN_HOSTS`、`CLOUD_HOST`、`CLOUD_USER`，Secret 值不得入库。

更新签名用于验证 WAO 更新来源，不能代替 Windows Authenticode 代码签名。没有组织代码签名证书时不宣称安装包已受 Windows 信任；需要证书的受管设备由管理员处理。

## macOS 接续开发

在 Mac 安装 Xcode Command Line Tools、Node.js 24 和 Rust，检出同一仓库，进入本目录执行 `npm ci`、`npm run desktop:dev`。`tauri.macos.conf.json` 使用标准系统窗口；共享全部工作区与更新 UI。

后续须在真实 Mac 验证子 WebView 布局、文件上传下载、飞书登录、关闭恢复及更新，再启用 macOS 构建 job、签名/公证和对应 `darwin-*` 更新平台。Windows 工作流不会伪造 macOS 已发布或阻塞 Windows 首发等待 Mac。

## 验收记录

验收结果写入 `docs/runbooks/desktop-client-v1-acceptance.md`。未验证的项目保留明确状态，不用界面预览或模拟接口代替实机与生产验收。
