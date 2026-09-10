# WAO 桌面客户端 1.0 验收

## 范围与入口

- 代码：`wao-client/`，PR #656。
- Windows x64 先交付，macOS 为后续 Mac 实机开发入口；没有 Mac 验收结论。
- 生产工作区复用 `https://wao.wayuwa.cn`；客户端更新通道为 `/downloads/desktop/client/`。
- 不把 Vue 浏览器预览、原生编译或 CI 制品当作真实账号验收。

## 已完成的本地检查

- `npm --prefix wao-client test`：10 项通过，覆盖更新下载/签名失败不安装、检查去重、更新中心切换保留 WebView、刷新取消、关闭隐藏、网络恢复不刷新和发布清单。
- `npm --prefix wao-client run build`：类型检查与生产构建通过。
- `bash deploy/scripts/test-render-nginx-config.sh`：通过。
- `bash deploy/jenkins/test-ci-change-plan.sh`：通过，包括仅客户端变化不部署主站。
- `bash -n wao-client/scripts/publish.sh` 与 `git diff --check`：通过。
- 外壳预览在 1280×720 和 800×560 下检查顶栏、更新中心、滚动和确认框居中；验证 Shift+Tab/Tab 焦点循环与 Escape 取消。此项仅证明外壳视觉与键盘交互。

## 当前环境事实

Windows Code Integrity 事件 3077/3033 拒绝 Rust 编译器的 `rustc_driver`/`std` DLL，原生本地编译因此不能完成。没有修改安全策略，也没有将该限制归因于业务代码。

CI Windows runner 已通过第二轮源代码的原生测试；最初托盘构造参数缺失已修正。最终版本仍须以对应 Git SHA 的 CI 结果为准。新增 CI 开发宿主允许在可执行该宿主的机器上使用 Vite 热更新；本机执行与安装能力须实际验证。

发布前验收构建 `fa039e07` 的 Windows CI `34425711972` 已成功；其安装包 SHA256 为 `77682F6B64B3D6F935A3516E8A6772CA1BBB62A0479377CB33F34702136D7F41`。在本机按当前用户静默安装返回 0，安装目录为 `C:\Users\wobus\AppData\Local\WAO`。实际启动后看到原生顶栏、完整线上 WAO 登录页和飞书二维码。客户端执行正常，不能把 Rust 编译器的 DLL 限制扩大描述为客户端不可运行。

上述安装包是发布前验证构建，不等于最终公开制品；发布后还须核对最终构建和摘要。真实账号登录由用户本人完成，当前没有据此宣称任务编辑、会话保留或更新已验收。

## 待完成的真实验收

- [ ] 最终代码的 Windows CI 测试、安装包和开发宿主构建成功。
- [ ] 实际安装、启动，完整工作区正常打开。
- [ ] 用户在客户端完成真实账号登录，查看与操作任务/文档，Web 同步可见。
- [ ] 未保存输入在关闭恢复、更新中心切换、取消刷新后保留。
- [ ] 外链、WAO 新窗口、文件选择和下载正常。
- [ ] 断网提示与用户主动重试可用，不自动覆盖本地输入。
- [ ] PR 合并；主站 Nginx 变更由成功 Jenkins 批次覆盖。
- [ ] Windows 安装包、签名、SHA256 与禁缓存更新清单发布成功。
- [ ] 旧版至新版实际安装更新成功，安装后会话与用户数据保留。

待验收项在完成后补充对应构建、版本或实机操作证据；有缺项时不得宣布 1.0 全部交付完成。
