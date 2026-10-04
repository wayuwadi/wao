# iOS 小记采集验收记录

范围：微信读书书摘、剪贴板确认、录音结束自动转写、原文/感想分离。依据 ADR-323；此轮不恢复 Android 或公开双平台下载。

## 已执行

- `npm run check`：移动端 46 项测试与 TypeScript/Vite 生产构建通过。覆盖转写失败保留音频及重试追加、失败草稿重开、成员迟到响应隔离、首次创建不同云端正文不覆盖、损坏草稿原数据保留、来源 URL 安全与 Markdown 特殊字符。
- `mvn -pl wao-server -am test -Dtest=BookExcerptProtectionTest,KnowledgeServiceTest,NoteAiManagedServiceTest,NoteAiWorkflowServiceTest,NotePolishVersionServiceTest -Dsurefire.failIfNoSpecifiedTests=false -q`：受影响测试通过。新断言覆盖 createOnly 原样返回最新正文/rev 且无写入/通知、书摘只将感想送到模型并原样拼回、空感想跳过正文润色、缺失/改变引用的派生写回拒绝。
- 真实既有 MySQL + 本地产品 main：专用验收成员通过真实登录和 HTTP 写入，固定 ID 新建 rev=1、另一个版本更新 rev=2，再以旧正文 createOnly 重试仍返回 rev=2；旧 expectedRev PATCH 返回 409；列表中只有一篇。原文前缀保持一致。验收连接的通知设置克隆为临时表关闭群发送；后续保存信号与旧工作流投影在本连接隔离，文档与登录保持真实持久化。阶段尝试表保留实际表，因为 MySQL 临时表不支持同一语句自关联查询；临时保存信号阻止共享调度器消费这批尝试。
- 浏览器 390×844：打开默认小记、新建输入、收起、刷新、本机草稿恢复、实际保存后返回列表；服务拒绝旧超长 ID 时输入保留，可另存兼容编号继续保存。引用阅读显示原始空行/双空格/特殊符号及独立感想。感想收起刷新恢复；另一设备更新至 rev=3 后，本机旧版本保存收到 409，显示云端感想供比较，经明确选择保留本机内容后以最新版本保存到 rev=4，书摘前缀未变。
- 浏览器验收截图：[感想冲突比较](assets/ios-notes-capture-2026-10-04/thoughts-conflict.png)、[保存后的引用阅读](assets/ios-notes-capture-2026-10-04/book-reader-saved.png)。这是浏览器 390×844 布局证据，不是真机截图。
- macOS/Xcode 26.2 第一次构建：[Actions 37196771499](https://github.com/wayuwacn/wao/actions/runs/37196771499) 的 iOS 模拟器构建与未签名设备归档通过，Android 和 publish 均跳过。第二次 [Actions 37197733820](https://github.com/wayuwacn/wao/actions/runs/37197733820) 已通过共享 Swift 格式断言、模拟器与设备归档；最终增强版本 [Actions 37199244329](https://github.com/wayuwacn/wao/actions/runs/37199244329) 也通过，check/iOS 成功，Android/publish 跳过。下载核对主应用与嵌入 WAOShare.appex 均为 1.2.0，扩展声明 com.apple.share-services；未签名 IPA SHA-256：`ceeb8038a901c34094a456170c53cfb379aaa09337787a1f1adffb4076c8e2bc`。

- 同步最新主线后 `mvn -pl wao-server -am test -q` 全量完成：1610 项测试，0 失败、0 错误、22 项依赖 Docker 的测试跳过。旧基线的两项 MCP 网络测试缺少 permission_policy 字段，主线修正后通过；未改动该测试能力。工程一致性检查 0 错误，5 项既有 ADR 重号警告保留。
- 合成验收小记最终版本 rev=4 已再次读取确认，三条本次验收小记移入正常回收站，其他小记未修改；验收服务与浏览器已收起。

## 真实发现与修复

Vue 响应式草稿不能直接 structuredClone，改为复制原始对象；旧移动端的带前缀 UUID 超出后端 32 位编号约束，统一改为 32 位十六进制 ID。第一次真实保存拒绝时本机草稿没有丢失。原图与 AI 文章配图分别标注，避免把生成图当成书摘证据。

## 尚待真机验收

当前主机为 Windows，iPhone 尚未安装；Apple Team、签名工具或 Mac 可用性尚未明确。未签名 IPA 不等于可直接安装。需用同一 Team 签名 App 和 WAOShare 并配置 App Group 与共享 Keychain 后，在 iPhone 12 上验证微信读书系统菜单实际显示/实际载荷、剪贴板授权、录音与听书暂停恢复、语音识别权限/离线、来电/锁屏/后台、软键盘/安全区和覆盖安装草稿保留。未执行项不以浏览器或模拟器编译代替。
