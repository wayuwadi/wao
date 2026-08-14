# WAO 原生 iOS 客户端

该目录是 SwiftUI 原生客户端，不嵌入网页，也不复制演示数据。首期直接连接
`https://wao.wayuwa.cn` 的真实会话、项目、任务、AI Run 和自动化接口。

## 构建

Linux 可以编辑源码和运行仓库其余测试，但 Apple SDK、SwiftUI 编译器和设备归档只随 Xcode 提供。
仓库的 `ios-build.yml` 会在 GitHub macOS runner 上生成工程、跑模拟器测试并上传未签名 IPA：

```bash
brew install xcodegen
sh wao-ios/scripts/prepare-app-icon.sh
xcodegen generate --spec wao-ios/project.yml
xcodebuild -project wao-ios/WAO.xcodeproj -scheme WAO \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=latest' test
```

## 本地签名和覆盖升级

CI 产物不含证书、Provisioning Profile 或账号凭据。使用本地签名工具安装时保持以下三项不变，新版本即可
覆盖旧版本，无需先卸载：

- Bundle ID：`cn.wayuwa.wao`
- 同一 Apple 身份或证书
- 相同的签名后 Bundle ID（签名工具不得每次随机改名）

每次发布递增 `MARKETING_VERSION` 和 `CURRENT_PROJECT_VERSION`。开发者免费签名通常只有短期有效期；稳定的
应用内一键更新需要 Apple Developer 账号、固定签名和可公开访问的已签名 IPA/manifest，或 TestFlight。
这些外部条件未接入前，客户端不会展示无法兑现的“检查更新”按钮。
