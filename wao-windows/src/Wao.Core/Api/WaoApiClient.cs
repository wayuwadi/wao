using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

namespace Wao.Core.Api;

public sealed class WaoApiClient
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = true
    };

    private readonly HttpClient _httpClient;

    public WaoApiClient(HttpClient httpClient)
    {
        _httpClient = httpClient;
    }

    public Task<T> GetAsync<T>(string path, CancellationToken cancellationToken = default) =>
        SendAsync<T>(new HttpRequestMessage(HttpMethod.Get, path), cancellationToken);

    public Task<TResponse> PostAsync<TResponse>(string path, CancellationToken cancellationToken = default) =>
        SendAsync<TResponse>(new HttpRequestMessage(HttpMethod.Post, path), cancellationToken);

    public Task<TResponse> PostAsync<TBody, TResponse>(
        string path,
        TBody body,
        CancellationToken cancellationToken = default) =>
        SendJsonAsync<TBody, TResponse>(HttpMethod.Post, path, body, cancellationToken);

    public Task<TResponse> PatchAsync<TBody, TResponse>(
        string path,
        TBody body,
        CancellationToken cancellationToken = default) =>
        SendJsonAsync<TBody, TResponse>(HttpMethod.Patch, path, body, cancellationToken);

    private Task<TResponse> SendJsonAsync<TBody, TResponse>(
        HttpMethod method,
        string path,
        TBody body,
        CancellationToken cancellationToken)
    {
        var request = new HttpRequestMessage(method, path)
        {
            Content = JsonContent.Create(body, options: JsonOptions)
        };
        return SendAsync<TResponse>(request, cancellationToken);
    }

    private async Task<T> SendAsync<T>(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        using (request)
        {
            request.Headers.Accept.ParseAdd("application/json");
            request.Headers.CacheControl = new() { NoCache = true };

            HttpResponseMessage response;
            try
            {
                response = await _httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                throw new WaoApiException("请求超时，请检查网络后重试");
            }
            catch (HttpRequestException exception)
            {
                throw new WaoApiException($"无法连接 WAO：{exception.Message}", exception);
            }

            using (response)
            {
                if (response.StatusCode == HttpStatusCode.Unauthorized)
                {
                    throw new WaoUnauthorizedException();
                }

                await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
                ApiEnvelope<T>? envelope;
                try
                {
                    envelope = await JsonSerializer.DeserializeAsync<ApiEnvelope<T>>(
                        stream,
                        JsonOptions,
                        cancellationToken).ConfigureAwait(false);
                }
                catch (JsonException exception)
                {
                    throw new WaoApiException("服务器返回了无法识别的数据", exception);
                }

                if (envelope is null)
                {
                    throw new WaoApiException("服务器返回了空响应");
                }

                if (!response.IsSuccessStatusCode || envelope.Code != 0)
                {
                    throw new WaoApiException(string.IsNullOrWhiteSpace(envelope.Message)
                        ? $"请求失败（HTTP {(int)response.StatusCode}）"
                        : envelope.Message);
                }

                return envelope.Data;
            }
        }
    }

    private sealed record ApiEnvelope<T>(int Code, string Message, T Data);
}

public class WaoApiException : Exception
{
    public WaoApiException(string message) : base(message) { }

    public WaoApiException(string message, Exception innerException) : base(message, innerException) { }
}

public sealed class WaoUnauthorizedException : WaoApiException
{
    public WaoUnauthorizedException() : base("登录已过期，请重新登录") { }
}
