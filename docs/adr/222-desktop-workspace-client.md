# ADR-222：Windows 优先的 WAO 桌面工作区客户端

- 状态：已采纳，依据本次桌面客户端 1.0 开发要求。
- 日期：2026-09-10

## 决策

在 `wao-client/` 新建完整工作区客户端，使用已有的 Tauri 2、Vue 3、TypeScript 技术；Windows x64 先交付，macOS 共享源码与原生适配入口，待 Mac 实机验收后单独启用发布。取代 ADR-077 对完整桌面工作区的停止开发决定；ADR-096 的桌宠和更新通道继续独立维护。

本地 Vue 外壳提供顶栏、连接状态、窗口控制与更新中心；独立 WebView 加载 WAO HTTPS 业务站点，保持与 Web 同源会话、路由和功能一致。远程网页没有 Tauri capabilities，不能调用文件系统、进程、更新器或外壳命令。外壳只允许固定命令，拒绝来自业务 WebView 的调用；外部链接仅允许 HTTP/HTTPS 并交给默认浏览器，飞书授权在业务 WebView 内完成。

业务页面继续只在 `wao-web/` 维护，由现有 Jenkins 发布。业务更新在用户下次打开或主动刷新时生效，不强制刷新正在编辑的页面。关闭窗口隐藏到托盘，保留未保存输入；退出、刷新、安装更新需要明确提醒保存。没有网络时保留已打开页面，仅提示离线，不自动重载或将缓存描述为最新数据。

桌面外壳以 `client-v<SemVer>` 标签或手动工作流发布。使用现有 GitHub Actions Windows runner、Tauri 更新签名 Secret 和云节点 SSH，独立发布到 `/downloads/desktop/client/`，复用已有 Nginx 父级路由。版本化文件先上传，清单最后原子替换；拒绝覆盖已发布版本及降级清单。更新先下载并验证签名，用户准备好后才安装。Windows 系统代码签名与更新签名分开描述，现有 Secret 不等于 Authenticode 证书。

## 维护和边界

- 不复制 Web 页面，不改变后端 API，不创建客户端专用业务数据。
- 开发命令支持本地 Web 地址，仅调试构建接受 loopback 地址；生产包固定 WAO HTTPS。
- 安装标识、会话数据目录和更新公钥保持稳定，支持覆盖升级；不与桌宠互相覆盖。
- Mac 源码与说明可准备，但 Windows 上的验证不能证明 macOS 已验收。
- Git 仓库为私有，用户下载和更新走 WAO 同域公开制品，无需在客户端保存 GitHub Token。

## 验收依据

见 `docs/specs/desktop-client-v1.md` 与 `wao-client/README.md`。必须验证安装、真实登录及业务操作、关闭恢复、断网恢复、签名更新与覆盖升级；不得用网页预览或构建通过代替实机验收。
