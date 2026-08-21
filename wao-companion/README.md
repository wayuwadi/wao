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

首次运行会打开 `https://wao.wayuwa.cn/settings?tab=companion&code=...`。配对成功后，设备令牌仅以 Windows
DPAPI CurrentUser 密文保存在 `%LOCALAPPDATA%\WAO\Companion\settings.json`。

首版是未签名便携包：解压后运行 `WAO Companion.exe`，不要直接在 ZIP 中启动。应用不请求管理员权限，也不
包含安装器或自动更新。
