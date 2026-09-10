# ADR-229 · 松风江湖集成到 WAO 移动端

日期：2026-09-10。状态：已采纳。

用户明确要求“融合到 WAO 的 APK 和 IPA 里面去”“游戏不单独搞一个 APP”。沿用 ADR-221 的 Capacitor 工程、cn.wayuwa.wao 包标识、Android 固定签名和 iOS 个人签名渠道；不创建独立游戏安装包。

今日页提供松风江湖入口。Android/iOS 使用官方 @capacitor/inappbrowser 的应用内 WebView 加载固定 https://game.wayuwa.cn，显示返回 WAO 工具栏。保留 Cookie，不传递 WAO Token；不改 server.url，不给远端游戏注入 WAO 原生桥，不开放任意 allowNavigation 或 iframe。Android 最低 API 28，确保插件的独立进程存储隔离。该最低版本变化需要在安装说明中标注。

游戏依旧由 wao-game 仓库在 Linux1 单机部署，独立账号、数据库、服务端权威存档。用户之后可选绑定 WAO 的约定未改变。打开窗口获取正式游戏页面，各端共用游戏内容版本；安装包版本独立演进，WAO 原生功能仍经原有构建签名流程更新。战斗中不强制刷新。Windows/macOS 的应用内入口仍是后续工作，不把手机集成声称为五端完成。

视觉沿用 WAO 今日页的卡片与图标语言，游戏内保持张三丰细腻卡通美术。本条是本轮人工方向记忆；后续调整须更新此决定及 wao-game/docs/memory。原生窗口、触控、返回、账号重开和覆盖安装应分别验收，浏览器及插件 Mock 不替代真机验证。
