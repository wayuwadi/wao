# ADR-094：新增原生 macOS 桌面宠物

## 状态

已采纳。

## 背景

ADR-093 以 .NET 8 WPF 恢复了窄范围 Windows 桌面伴侣。用户接下来会在 Mac 上继续开发，并要求桌宠在 macOS
保留配对、状态提醒、透明浮窗、拖动、活动抽屉和菜单栏入口。WPF 不能运行在 macOS；为追求单一 UI 技术而
立即迁移 Avalonia 会重写已经验收的 Windows 窗口、托盘、DPAPI 和启动行为，扩大本次风险。

## 决策

- 保留 `Wao.Companion.Windows` 的 WPF 实现，新增 Swift 5.9 / SwiftUI + AppKit 的
  `Wao.Companion.Mac`；两个客户端消费同一 Companion HTTPS API 和同一 4×3 原创像素精灵图。
- macOS 令牌保存到 Keychain，窗口位置与非敏感偏好保存到 UserDefaults；不把令牌写入 plist、日志或仓库。
- macOS 使用透明浮动 `NSPanel`、菜单栏 `NSStatusItem` 和 `SMAppService.mainApp` 登录启动；不申请辅助功能、
  文件、摄像头、麦克风或桌面自动化权限。
- macOS 首发支持 13+，CI 构建 Apple Silicon / Intel universal `.app`，使用 ad-hoc 签名并压缩为 ZIP。
  Developer ID 签名、公证、DMG、自动更新继续留在后续发布能力中。
- 平台专属 UI 不共享源码；状态枚举、聚合优先级、API 路径、轮询频率与安全边界必须通过规格和对等测试保持一致。

## 影响

- main CD 在部署前同时等待 Windows 与 macOS 客户端构建，任一平台编译或测试失败都不发布不完整下载集。
- 当前 Windows 环境不能证明 Swift 编译和 macOS 实机交互；合并前以 macOS GitHub runner 为静态/单元门禁，
  首次发布仍必须在真实 Mac 完成 Keychain、透明浮窗、拖动、菜单栏与网页配对验收。
- ADR-077 与 ADR-093 的窄伴侣边界继续有效；macOS 客户端不恢复原生业务工作台，也不获得通用 WAO Session。
