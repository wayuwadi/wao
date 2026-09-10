# 1.0 验收记录

2026-09-10 本地实现验证：
- `npm ci` 正常；`npm audit` 0 个已知漏洞。
- 17 项 Vitest 测试通过；TypeScript 检查、Vite 生产构建通过。
- Capacitor Android/iOS 工程已生成，固定包名 cn.wayuwa.wao；图标生成通过。
- 390×844 浏览器演示模式实点：新建待办 → 今日列表 → 日历当日可见；小记输入 → 收起 → 刷新 → 恢复标题和正文通过。
- 演示模式只在显式 `vite --mode demo` 启动，页面顶部标注；不等于真实 API/设备验收。
- 工作流 YAML 结构检查和 iOS 构建脚本语法检查通过。

首次云端构建（PR #657，e10415bf）：https://github.com/wayuwacn/wao/actions/runs/34426581403 全部通过。Android APK/AAB、签名校验和 lintRelease 成功；iOS 模拟器构建和未签名设备 IPA 归档成功。

后续小记响应丢失重试保护增加 2 项测试，本地总计 17 项通过；正式标签产物继续以对应 Release 的 Actions 结果为准。

仍需用户设备验收：个人签名安装、真实账号登录同步、覆盖升级。
