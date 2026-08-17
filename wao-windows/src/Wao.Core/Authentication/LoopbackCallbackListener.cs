using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Wao.Core.Authentication;

/// <summary>
/// Receives one OAuth callback on a random loopback port without requiring a Windows URL reservation.
/// </summary>
public sealed class LoopbackCallbackListener : IDisposable
{
    private const string CallbackPath = "/wao-auth/callback";
    private readonly TcpListener _listener;
    private bool _disposed;

    private LoopbackCallbackListener(TcpListener listener)
    {
        _listener = listener;
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        CallbackUri = new Uri($"http://127.0.0.1:{port}{CallbackPath}");
    }

    public Uri CallbackUri { get; }

    public static LoopbackCallbackListener Start()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start(1);
        return new LoopbackCallbackListener(listener);
    }

    public async Task<OAuthCallback> WaitForCallbackAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            using var client = await _listener.AcceptTcpClientAsync(cancellationToken).ConfigureAwait(false);
            await using var stream = client.GetStream();
            using var reader = new StreamReader(stream, Encoding.ASCII, false, 1024, leaveOpen: true);
            var requestLine = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
            var parts = requestLine?.Split(' ', 3) ?? [];
            if (parts.Length != 3 || parts[0] != "GET")
            {
                await WriteResponseAsync(stream, 400, "飞书登录回调无效，请返回 WAO 重试。", cancellationToken).ConfigureAwait(false);
                throw new InvalidOperationException("飞书登录回调格式无效");
            }

            var callback = new Uri(CallbackUri, parts[1]);
            var query = ParseQuery(callback.Query);
            if (callback.AbsolutePath != CallbackPath
                    || !query.TryGetValue("code", out var code) || string.IsNullOrWhiteSpace(code)
                    || !query.TryGetValue("state", out var state) || string.IsNullOrWhiteSpace(state))
            {
                await WriteResponseAsync(stream, 400, "飞书登录回调缺少必要信息，请返回 WAO 重试。", cancellationToken).ConfigureAwait(false);
                throw new InvalidOperationException("飞书登录回调缺少授权码");
            }

            await WriteResponseAsync(stream, 200, "飞书授权已完成，可以关闭此页面并返回 WAO 客户端。", cancellationToken).ConfigureAwait(false);
            return new OAuthCallback(code, state);
        }
        catch (SocketException) when (cancellationToken.IsCancellationRequested)
        {
            throw new OperationCanceledException(cancellationToken);
        }
    }

    private static Dictionary<string, string> ParseQuery(string query)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var parts = pair.Split('=', 2);
            if (parts.Length == 2)
            {
                values[Uri.UnescapeDataString(parts[0])] = Uri.UnescapeDataString(parts[1].Replace('+', ' '));
            }
        }
        return values;
    }

    private static async Task WriteResponseAsync(
        NetworkStream stream,
        int statusCode,
        string message,
        CancellationToken cancellationToken)
    {
        var title = statusCode == 200 ? "WAO 登录完成" : "WAO 登录未完成";
        var body = $$"""
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>{{title}}</title>
            <style>body{font-family:system-ui,sans-serif;display:grid;min-height:90vh;place-content:center;background:#f6f7fb;color:#172033}main{max-width:440px;padding:32px;border:1px solid #e1e4ec;border-radius:18px;background:white;text-align:center}h1{font-size:24px}p{color:#697386;line-height:1.7}</style>
            </head><body><main><h1>{{title}}</h1><p>{{message}}</p></main></body></html>
            """;
        var bodyBytes = Encoding.UTF8.GetBytes(body);
        var reason = statusCode == 200 ? "OK" : "Bad Request";
        var headers = Encoding.ASCII.GetBytes(
            $"HTTP/1.1 {statusCode} {reason}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {bodyBytes.Length}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n");
        await stream.WriteAsync(headers, cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync(bodyBytes, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _listener.Stop();
    }
}

public sealed record OAuthCallback(string Code, string State);
