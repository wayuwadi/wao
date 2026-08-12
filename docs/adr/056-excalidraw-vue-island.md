# ADR-056：用隔离的 React 岛接入 Excalidraw

## 状态

已采纳。

## 上下文

WAO Web 的应用框架是 Vue 3，个人草图需要复用 Excalidraw 成熟的绘图工具。
Excalidraw 官方可嵌入编辑器只以 React 组件包提供；直接 iframe 嵌入
`excalidraw.com` 无法让 WAO 可靠地获取画布变更，也就无法按当前成员保存、恢复和做版本冲突保护。

## 决策

- 引入官方 `@excalidraw/excalidraw` 包及其 React peer dependencies。
- React 只在 `DrawingCanvas.vue` 的一个 DOM 容器内挂载，通过 Vue props/events 交换场景；
  路由、页面状态、文件管理和 API 仍全部使用 Vue 3。
- 草图路由惰性加载，因此 React 与 Excalidraw 不进入首页初始包。
- 不复刻、修改或内嵌 Excalidraw 仓库源码；仅使用其 MIT 许可的 npm 发布包，后续升级以官方嵌入 API 为边界。
- 场景保存、身份、文件列表与冲突控制由 WAO 实现，不依赖 Excalidraw 官网的本地存储或共享服务。

## 后果

- 用户获得与 Excalidraw 一致的画布工具，文件却是 WAO 账号下的真实持久化数据。
- 草图功能块会带来较大的惰性加载资源；不进入草图的用户不下载它。
- 仓库同时存在 Vue 和被封装的 React 运行时，但 React 不得向其它业务页面扩散；
  如果官方未来提供框架无关的 Web Component，可在不改业务契约的前提下替换该岛。
