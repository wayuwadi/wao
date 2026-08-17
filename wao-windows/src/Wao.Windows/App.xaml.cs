using System.Net;
using System.Net.Http;
using System.Windows;
using Wao.Core.Api;
using Wao.Core.Updates;

namespace Wao.Windows;

public partial class App : Application
{
    internal static readonly Uri ApiBaseUri = new("https://wao.wayuwa.cn");
    internal static readonly Uri UpdateManifestUri = new("https://wao.wayuwa.cn/downloads/windows/latest.json");

    internal CookieContainer Cookies { get; } = new();
    internal WaoApiClient Api { get; private set; } = null!;
    internal UpdateService Updates { get; private set; } = null!;
    internal SessionCookieStore CookieStore { get; } = new(ApiBaseUri);

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        CookieStore.Restore(Cookies);
        var handler = new HttpClientHandler
        {
            CookieContainer = Cookies,
            UseCookies = true,
            AutomaticDecompression = DecompressionMethods.All
        };
        var apiHttpClient = new HttpClient(handler)
        {
            BaseAddress = ApiBaseUri,
            Timeout = TimeSpan.FromSeconds(30)
        };
        Api = new WaoApiClient(apiHttpClient);
        Updates = new UpdateService(new HttpClient { Timeout = TimeSpan.FromMinutes(10) }, UpdateManifestUri);
        MainWindow = new MainWindow();
        MainWindow.Show();
    }
}
