using Wao.Core.Authentication;

namespace Wao.Core.Tests;

public sealed class LoopbackCallbackListenerTests
{
    [Fact]
    public async Task ReceivesAuthorizationCodeOnRandomLoopbackPort()
    {
        using var listener = LoopbackCallbackListener.Start();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var callbackTask = listener.WaitForCallbackAsync(timeout.Token);
        var callbackUri = new Uri(listener.CallbackUri + "?code=auth-code&state=signed-state");

        using var http = new HttpClient();
        var response = await http.GetAsync(callbackUri, timeout.Token);
        var callback = await callbackTask;

        Assert.Equal("127.0.0.1", listener.CallbackUri.Host);
        Assert.True(listener.CallbackUri.Port > 0);
        Assert.Equal("auth-code", callback.Code);
        Assert.Equal("signed-state", callback.State);
        Assert.True(response.IsSuccessStatusCode);
        Assert.Contains("返回 WAO 客户端", await response.Content.ReadAsStringAsync(timeout.Token));
    }
}
