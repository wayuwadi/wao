using System.Windows;

namespace Wao.Companion.Windows;

public partial class App : System.Windows.Application
{
    private AppController? _controller;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        _controller = new AppController();
        _ = _controller.StartAsync();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _controller?.Dispose();
        base.OnExit(e);
    }
}
