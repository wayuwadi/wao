using System.Windows;

namespace Wao.Companion.Windows;

public partial class App : System.Windows.Application
{
    private AppController? _controller;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        bool previewMode = e.Args.Any(arg => string.Equals(arg, "--preview", StringComparison.OrdinalIgnoreCase));
        _controller = new AppController(previewMode);
        _ = _controller.StartAsync();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _controller?.Dispose();
        base.OnExit(e);
    }
}
