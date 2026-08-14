# ADR-070：原生 iOS 客户端与未签名 CI 设备包

## 状态

已采纳，取代 ADR-069。

## 背景

任务 `t17866728549710` 明确要求原生 iOS 客户端，不接受主屏幕 Web App 或 WebView 包装，同时希望在
Linux 上继续开发、先形成可测试版本，并允许使用本地工具自行签名。后续版本应能覆盖安装，不能要求先卸载。

Linux 能编写 Swift 源码，但 SwiftUI、iOS SDK、模拟器和设备归档属于 Xcode，不能在 Linux runner 上编译或
验收。Apple 也不允许未签名应用在真机运行。仓库当前没有 Apple Developer Team、证书、Provisioning
Profile、已登记设备或 TestFlight 发布通道，因而不能在服务器上伪造“可直接安装”或“应用内一键更新”。

## 决策

- 新增 `wao-ios/` SwiftUI 原生客户端；界面、导航、状态管理和网络访问均使用 Apple 原生框架，不嵌入
  WAO 网页。
- 首期覆盖上一版范围中有真实接口支撑的核心链路：邮箱会话登录、项目与任务、任务完成状态、AI 目标派发与
  运行记录、自动化运行/暂停/恢复/重新授权。
- 客户端固定连接 WAO HTTPS 生产 API，使用服务端 `WAO_SESSION` Cookie；不在本机保存密码，不缓存私有
  业务响应冒充最新数据。飞书原生登录需要新的安全回调协议，首期不以 WebView 冒充原生登录。
- GitHub Actions 使用 macOS runner 和 Xcode 构建，在模拟器上执行测试，再生成未签名设备 IPA 作为构建
  产物。仓库、日志和产物均不包含签名凭据。
- Bundle ID 固定为 `cn.wayuwa.wao`。本地签名时保持 Bundle ID 和 Apple 签名身份不变并递增版本号，iOS
  可覆盖安装，无需卸载旧版本。
- 在接入固定签名与发布源前不展示“检查更新”按钮。取得 Apple Developer 条件后，优先选择 TestFlight；
  如确需自托管 OTA，另行设计已签名 IPA、manifest、HTTPS 托管、设备范围与凭据轮换。
- 撤下 ADR-069 引入的 Web App 清单、Apple 独立窗口声明和 iOS 安装引导；移动 Web 本身及独立有用的 AI
  快速入口继续保留。

## 影响

- 日常源码开发仍可在 Linux 完成，Apple 平台的编译与自动测试由 macOS CI 提供。
- CI 产物可交给用户已有的本地签名工具；没有签名材料时无法完成真机安装验收，这是 Apple 平台约束，不以
  演示包或伪造状态绕过。
- 免费开发签名通常存在较短有效期；稳定覆盖升级需要同一签名身份。真正的一键更新仍以签名发布通道就绪为
  前置条件。
