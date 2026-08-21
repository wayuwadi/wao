# Phase 1：WAO Windows / macOS 桌面宠物

## 目标

在 Windows 10/11 x64 与 macOS 13+（Apple Silicon / Intel）提供原创 WAO 桌面宠物。宠物常驻桌面并汇总
当前成员自己发起的 AI Run；所有处理仍回到 WAO Web 完成，不建设第二套业务工作台。

## 用户路径

1. 用户下载对应平台便携 ZIP；Windows 运行 `WAO Companion.exe`，macOS 打开 `WAO Companion.app`。首次启动显示短码并自动打开
   `/settings?tab=companion&code=...`；用户登录 WAO 后确认设备名称。
2. 客户端完成配对后把设备令牌保存到系统安全存储：Windows 使用 DPAPI CurrentUser，macOS 使用 Keychain；
   随后关闭设置窗口并显示透明置顶桌宠。服务端撤销设备或令牌失效后，客户端清除本地令牌并回到重新配对状态。
3. 桌宠每 5 秒读取一次活动；隐藏后每 30 秒读取。断网时显示灰色离线提示并退避重试，不把网络错误算成业务
   `BLOCKED`。
4. 桌宠使用原创 WAO 紫焰精灵逐帧动画：空闲或完成状态会短距离自主行走；按住拖动时切换为悬空动作，松手后
   播放落地动作并保存位置。单击桌宠打开贴附活动抽屉；抽屉显示时暂停自主移动。选择活动后先确认已读，再用
   默认浏览器打开 `/projects/{projectId}?task={taskId}`。隐藏状态和开机启动偏好也在当前 Windows 用户下持久化。
5. 设置页可以查看配对设备、最后在线时间并撤销。设备令牌只能读取/确认自己的桌宠活动，不能调用其他 API。

## 状态规则

| AI Run | 桌宠状态 | 是否提醒 |
|---|---|---|
| `queued` / `claimed` / `running` | `RUNNING` | 始终展示 |
| `failed` 且 `exitCode = 3` | `NEEDS_INPUT` | 未读时 |
| 其他 `failed` | `BLOCKED` | 未读时 |
| `succeeded` | `READY` | 未读时 |
| `cancelled` | 不展示 | 否 |

聚合优先级为 `NEEDS_INPUT > BLOCKED > READY > RUNNING > IDLE`。新设备不补发配对前已结束的历史运行；
活动抽屉最多显示所有进行中运行和最近七日内 20 条已结束运行。

## 安全与交付

- 配对短码十分钟过期；轮询密钥和设备令牌均为高熵随机值，服务端只存 SHA-256。批准必须来自已有 WAO
  服务端 Session，令牌明文不得进入数据库、日志、仓库或错误消息。
- 客户端本地只保存系统安全存储中的设备令牌、随机设备 ID、窗口位置和用户偏好，不保存密码或业务响应。
- main 工作流分别在 Windows 和 macOS runner 运行测试，发布自包含 `win-x64` ZIP 与 ad-hoc 签名的
  `macOS universal` ZIP；公开下载同时提供 SHA-256。
- 首版不含聊天输入、语音、桌面自动化、文件访问、本地 AI、安装器和自动更新；macOS 包尚未做 Developer ID
  签名或公证，首次打开可能需要用户在系统提示中确认。

## 验收

- 服务端覆盖配对过期、密钥错误、批准/消费、设备撤销、成员隔离、状态映射、优先级和已读规则。
- Web 覆盖登录回跳、配对确认、过期/重复批准、设备列表和撤销反馈。
- Windows 实机覆盖透明置顶、首次位置、逐帧边界、空闲行走、拖动悬空与落地动作、多显示器/DPI、托盘、
  开机启动、减少动画、断网恢复和重启恢复连接。
- macOS 实机覆盖菜单栏、透明浮窗、跨 Space、Retina 缩放、拖动/慢速行走、Keychain、登录时启动、断网恢复、
  Apple Silicon 与 Intel universal 包启动；当前 Windows 开发机只允许静态检查，必须在 Mac 上补做本项。
- 线上用真实账号完成 `配对 → 真实 AI Run RUNNING → READY → 深链 → 已读清除 → 撤销设备`。
