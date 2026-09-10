# ADR-221：移动端 1.0 共用 Vue 与 Capacitor

## 状态
已采纳。依据 2026-09-10 用户接续多端分工讨论，要求一人负责 Android＋iOS、同功能并可快速本地迭代。用户已明确选择 Vue＋Capacitor，并说明当前两人使用、无 Apple 开发者账号，iOS 先由用户个人签名。

## 决策
- 新增独立 `wao-mobile/`，使用 Vue 3、TypeScript、Vue Router、Capacitor 8；专用移动 UI 与 API 适配层，不直接加载远程网站。移动界面使用自有轻量样式与 Lucide 图标，避免引入桌面 Ant Design 交互。
- 就新移动端 1.0 替代 ADR-070 的 SwiftUI-only 要求；保留 `wao-ios/` 历史代码，旧流水线仅保留手动运行，避免相同 Bundle ID 两条自动发布线。Web、桌面端技术栈不变。
- API 适配器位于 `wao-mobile/src/api/`，组件只经工作区 composable 调用；不提前抽取全仓共享包。使用现有 HTTPS API、系统 HTTP 会话 Cookie，不复制密码或 token 到 localStorage。
- 今日、任务、日期日历、小记为同一份功能清单；任务项目与身份必须来自服务端。日历 1.0 按任务截止日期组织，不另建日程数据库。富文本小记可阅读，先保留原文结构；正文编辑仅针对纯段落小记，避免降级覆盖。
- 本地草稿按账号分区且刷新可恢复，失败保留；成功以服务端返回为准，正文修改提交 expectedRev，冲突可保存副本。退出前提示本机草稿保留。
- 移动 CI 与 Jenkins/desktop 解耦：main 的移动路径变更做校验与双平台未签名/调试构建；mobile-vX.Y.Z 标签或手动发布触发独立签名流程，Android 发布固定签名 APK/AAB，iOS 产出明确命名的 unsigned IPA 交给用户个人签名。Android 缺签名明确失败；iOS 不要求不存在的 Apple 开发者账号，也不宣称未签名 IPA 可直接安装。
- 同 Bundle ID、同签名、单调构建号支撑覆盖升级。1.0 不引入远程热更新代码；日常 Vite HMR，设备 cap run，版本发布一个命令打标签。

## 验证边界
本地需 Node 22+、Android SDK/JDK 21、Xcode 26+。签名配置、设备安装、跨端真实同步、覆盖升级和 TestFlight 接收分别记录证据。应用商店公开上架另有商店资料和审核，不能用 CI 成功替代。
