# WAO 跨平台桌面宠物

这是 WAO 的轻量桌面陪伴入口，使用 Tauri 2 + Vue 3，同时面向 Windows x64 与 macOS Universal。它不复制 WAO Web 的业务页面，只负责透明置顶宠物、系统托盘、快速打开 WAO 与签名更新。

## 本地开发

```bash
npm ci
npm run test
npm run build
npm run tauri dev
```

macOS 原生构建需要完整 Xcode，Windows 构建需要 WebView2 与 MSVC。前端构建不依赖原生工具链。

## 发布

在 GitHub Actions 手动运行 `WAO Desktop Release`，输入一个高于当前版本的 SemVer 和简短更新说明。流水线会：

1. 在 Windows Runner 生成 x64 NSIS 安装器和签名更新包；
2. 在 macOS Runner 生成同时覆盖 Intel / Apple Silicon 的 Universal DMG 和签名更新包；
3. 生成 Tauri 静态更新清单 `latest.json`；
4. 把稳定安装链接、版本化更新包和清单发布到 `https://wao.wayuwa.cn/downloads/desktop/`。

更新私钥只保存在 GitHub Secret `TAURI_SIGNING_PRIVATE_KEY`。不要重新生成或覆盖这把密钥，否则已有安装将无法接受后续更新。
