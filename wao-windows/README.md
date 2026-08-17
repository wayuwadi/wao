# WAO 原生 Windows 客户端

这是使用 .NET 8 WPF 编写的原生 `win-x64` 客户端，不包含 WebView 或演示业务数据。它直接连接 WAO 的真实
会话、项目、任务、AI Run 和自动化接口，并带有可拖动的桌面宠物与内置更新器。

## 首次使用

1. 下载 `https://wao.wayuwa.cn/downloads/windows/WAO-win-x64.zip`。
2. 解压到当前用户可写且长期保留的目录，例如 `%LOCALAPPDATA%\Programs\WAO`。
3. 运行 `WAO.exe`，点击“飞书扫码登录”并在系统浏览器完成授权；也可使用已绑定的邮箱密码登录。

当前没有 Authenticode 证书，Windows 首次运行可能显示 SmartScreen 提示。更新包来自 WAO HTTPS 地址并在
安装前校验发布清单中的 SHA-256；不要把程序放进需要管理员权限才能写入的目录，否则内置更新无法替换文件。

## 构建与测试

```powershell
dotnet test wao-windows/tests/Wao.Core.Tests/Wao.Core.Tests.csproj -c Release
dotnet publish wao-windows/src/Wao.Windows/Wao.Windows.csproj -c Release -r win-x64 --self-contained true
dotnet publish wao-windows/src/Wao.Updater/Wao.Updater.csproj -c Release -r win-x64 --self-contained true
```

Linux 可运行 `Wao.Core.Tests` 并使用 `EnableWindowsTargeting` 交叉编译；真实窗口、DPAPI、桌宠交互和更新替换由
Windows runner 及 Windows 实机验收。
