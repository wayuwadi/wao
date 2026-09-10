# WAO 桌面客户端 1.0 验收

更新日期：2026-09-10。Windows 1.0 已发布并由用户确认验收；macOS 按计划留待 Mac 实机阶段。本记录区分自动检查、AI 实机操作和用户确认，不把未逐项复测的场景记为 AI 已测试。

## 交付与发布证据

- 实现：[PR #656](https://github.com/wayuwacn/wao/pull/656)，合并提交 `44190554472789ef7fdc5edb9128f4eb55de017e`。
- 开发宿主进程隔离：[PR #661](https://github.com/wayuwacn/wao/pull/661)，合并提交 `07d0295e6529b2e9cd4d4885bd057974defec798`；此修复只涉及开发脚本，无需覆盖正式 1.0.0 安装包。
- 正式标签 `client-v1.0.0` 的 [Windows 工作流 34428076156](https://github.com/wayuwacn/wao/actions/runs/34428076156) 成功，完成前端检查、Rust 测试、安装包与开发宿主构建、签名、发布和公开制品核验。
- 主站 Nginx 变更由 [Jenkins #321](https://wao.wayuwa.cn/jenkins/job/wao-main-cd/321/) 成功覆盖；修复提交对应 [Jenkins #323](https://wao.wayuwa.cn/jenkins/job/wao-main-cd/323/) 也已成功。
- [固定下载](https://wao.wayuwa.cn/downloads/desktop/client/WAO-windows-x64-setup.exe) 与 [版本安装包](https://wao.wayuwa.cn/downloads/desktop/client/WAO_1.0.0_windows-x64-setup.exe) 均已下载核对，与 CI 制品一致：3,121,211 字节，SHA256 为 `e4335f6c87cad51971224ab3dcd610bb71698fc95c90150b419d8d32f828347d`。
- [更新清单](https://wao.wayuwa.cn/downloads/desktop/client/latest.json) 返回 HTTP 200、版本 1.0.0，Cache-Control 为 `no-store, no-cache, must-revalidate`。
- 使用客户端内置公钥核验公开安装包的 Ed25519 签名及 trusted comment 签名，均通过；归档 Cargo.lock 与源代码锁文件一致。
- Windows Authenticode 状态为 NotSigned；有效的 Tauri 更新签名不代表操作系统代码签名。

## 自动检查与安装验证

- 修复后 `npm --prefix wao-client test`：13 项通过，覆盖更新失败不安装、检查去重、保留 WebView、刷新取消、关闭隐藏、网络恢复不刷新、发布清单和开发宿主文件准备。
- `npm --prefix wao-client run build`：类型检查与生产构建通过。
- Nginx 配置测试、Jenkins 变更规划测试、发布脚本语法检查和 `git diff --check` 通过。
- 外壳预览在 1280×720 和 800×560 检查布局、滚动、确认框居中和键盘焦点；此项仅证明预览交互。
- 正式公开安装包从内部 0.9.0 覆盖安装至 1.0.0，安装退出码 0；已安装程序 ProductVersion 为 1.0.0。此项是安装器覆盖升级，不能等同应用内升级全流程。
- 修复后开发宿主以 `dev-host/runtime/wao-client-dev.exe` 运行；正式安装前后 PID 均为 24136，安装后 Responding=True，证实安装器不再因同名进程关闭该宿主。

## AI 已完成的正式客户端实机操作

以下操作发生于已安装的正式 1.0.0、真实登录账号下，使用一条明确标注用途的验收小记：

1. 完整线上工作区正常显示，输入验收文字后出现浏览器草稿已保存提示。
2. 保留未提交草稿打开更新中心，检查更新返回当前 1.0.0、已是最新；回到工作区后文字完整。
3. 打开刷新确认框并取消，确认框居中，草稿保留。
4. 关闭窗口隐藏到托盘后，生产进程仍响应；通过单实例激活重新打开，原工作区与草稿保留。
5. 保存小记后显示“已保存到服务器，清单已更新”，卡片显示“已保存”；打开“完整文档”可读回完整内容。
6. 编辑器图片按钮唤起原生 Windows 文件选择框；选择过程被中止，没有把文件上传记为完成。

验收小记创建于 2026-09-10 约 11:02，正文以“Windows 客户端 1.0 实机验收 20260910”开头。用户继续使用客户端后，未再操作或清理该记录。

## 用户确认与复测边界

2026-09-10，用户明确表示：

> 我已经验收过了，电脑我还在用，就不用你验收了，继续做其他的动作

据此记录用户验收通过，并停止 AI 的电脑操作、安装、重启及界面复测。此前的锁屏、Esc 中止与待恢复询问已不再作为交付阻塞。

文件上传完成、下载、外链/新窗口、离线重试、源码热更新的视觉结果，以及应用内下载更新到重启的完整流程，没有在本记录中逐项取得 AI 最终复测证据。用户总体确认不转换为这些场景逐项由 AI 测试通过的声明。

## 本地维护与 Mac 接续

- 同仓库源码：`wao-client/`；业务页面继续由 `wao-web/` 维护。开发、版本号、标签发布流程见 [客户端 README](../../wao-client/README.md)。
- 在仓库根目录运行 `npm --prefix wao-client run desktop:dev:host`，使用已下载的 CI 开发宿主迭代 Vue/TypeScript/CSS；Rust 改动需重新构建宿主。
- 本机 Code Integrity 拒绝 Rust 编译器 DLL，原生构建由 Windows CI 完成；已编译客户端可以执行，没有修改系统安全策略。
- 普通 Web 变更使用现有 Jenkins；外壳版本通过 `client-v<版本>` 标签触发独立构建发布，已发布版本不可覆盖。
- Mac 使用同一工程和 `tauri.macos.conf.json`，在真实 Mac 完成登录、布局、文件、窗口恢复、更新验证后，再启用 macOS 构建、签名/公证与更新平台；当前没有 Mac 发布或验收结论。
