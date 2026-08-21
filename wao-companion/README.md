# WAO Companion

WAO Companion 是 Windows 10/11 x64 的窄桌面伴侣。它只读取当前配对成员自己的 AI Run 聚合状态，点击活动
后回到 WAO Web；不复制项目、任务、知识库或 Runner 管理页面。

## 本地验证

```powershell
dotnet test wao-companion/tests/Wao.Companion.Core.Tests/Wao.Companion.Core.Tests.csproj
dotnet test wao-companion/tests/Wao.Companion.Windows.Tests/Wao.Companion.Windows.Tests.csproj
dotnet build wao-companion/src/Wao.Companion.Windows/Wao.Companion.Windows.csproj -c Release
dotnet publish wao-companion/src/Wao.Companion.Windows/Wao.Companion.Windows.csproj -c Release -r win-x64 --self-contained true
```

服务端尚未部署时，可使用 `WAO Companion.exe --preview` 打开仅供本地视觉验收的桌宠与示例活动抽屉。
预览模式不发起配对、不写入设备令牌，也不读取线上业务数据；不带参数启动时仍进入正式配对流程。

桌宠采用 4×3 原创紫焰精灵图：单击切换活动抽屉，按住可拖动，松手播放落地动作；活动抽屉关闭后，空闲或
已完成状态会间歇短距离行走。启用 Windows“减少动态效果”后固定显示代表帧并停止自主移动。

首次运行会打开 `https://wao.wayuwa.cn/settings?tab=companion&code=...`。配对成功后，设备令牌仅以 Windows
DPAPI CurrentUser 密文保存在 `%LOCALAPPDATA%\WAO\Companion\settings.json`。

首版是未签名便携包：解压后运行 `WAO Companion.exe`，不要直接在 ZIP 中启动。应用不请求管理员权限，也不
包含安装器或自动更新。
