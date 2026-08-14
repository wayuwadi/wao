# ADR-069：iOS 首期采用主屏幕 Web App

## 状态

已被 [ADR-070](070-native-ios-client-and-unsigned-ci-package.md) 取代。任务 `t17866728549710` 明确拒绝
主屏幕 Web App 形态，相关安装入口已撤下；本 ADR 只保留为决策历史。

## 背景

WAO 已有适配手机安全区的 Vue 应用、统一登录和项目 / AI Run / 自动化真实接口，但当前发布基础设施只有
Linux 构建与 Web 静态制品部署。仓库和运行环境没有 Apple Developer Team、签名证书、Provisioning
Profile、TestFlight 或 App Store 发布通道，也无法在 Linux CI 中完成可安装 IPA 的签名与实机验收。

仅提交一个未经签名的 Xcode 或 Capacitor 空壳，不会给用户产生可安装结果；复制前端数据做原生演示还会
违反 WAO 的真实数据原则。

## 决策

首期 iOS 版本采用随 WAO 主站一同发布的主屏幕 Web App：

- Web App Manifest 提供 WAO 名称、图标、品牌色、独立窗口和项目 / AI 派发 / 自动化快捷入口；
- Apple 专用 meta 与现有 `viewport-fit=cover` 组成 iPhone 刘海、状态栏和底部手势区的完整应用壳；
- 登录后向尚未独立运行的 iOS / iPadOS 用户展示一次“分享 → 添加到主屏幕”说明；
- 手机底部导航提供不依赖 iOS 清单快捷方式支持情况的“AI”入口，直接定位到真实目标派发区；
- 所有业务能力继续使用现有 API、服务端会话和实时状态，知识库等模块保留现有真实导航入口；
- 不注册离线 Service Worker，不缓存私有业务响应。安装形态不等于离线能力，避免旧任务或旧 AI 状态被
  误认为线上最新结果。

## 影响

- 本次部署完成后即可在 iPhone 上安装和使用，无需等待额外商店审核或签名基础设施。
- 首期不能提供 APNs、系统分享扩展、后台原生任务等 iOS 专属能力；Web Push 或原生能力需要后续独立规格。
- 如果以后提供 Apple 开发者账号、签名凭据管理、macOS CI 与 TestFlight 验收设备，可新增 ADR 评估
  Capacitor 包装或 Swift 原生客户端；届时仍以现有服务端 API 为唯一真实数据源。
