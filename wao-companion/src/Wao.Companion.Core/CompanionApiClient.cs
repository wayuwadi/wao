using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

namespace Wao.Companion.Core;

public sealed class CompanionApiClient
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = true,
    };

    private readonly HttpClient _http;

    public CompanionApiClient(HttpClient http) => _http = http;

    public Task<PairingCreated> CreatePairingAsync(CreatePairingRequest request, CancellationToken cancellationToken) =>
        SendAsync<PairingCreated>(HttpMethod.Post, "/api/public/companion/pairings", request, null, cancellationToken);

    public Task<PairingPoll> PollPairingAsync(string pairingId, string pollSecret, CancellationToken cancellationToken) =>
        SendAsync<PairingPoll>(HttpMethod.Post,
            $"/api/public/companion/pairings/{Uri.EscapeDataString(pairingId)}/poll",
            new { pollSecret }, null, cancellationToken);

    public Task<CompanionSnapshot> GetActivitiesAsync(string deviceToken, CancellationToken cancellationToken) =>
        SendAsync<CompanionSnapshot>(HttpMethod.Get, "/api/public/companion/activities", null,
            deviceToken, cancellationToken);

    public async Task MarkReadAsync(string deviceToken, string activityId, CancellationToken cancellationToken) =>
        await SendAsync<object?>(HttpMethod.Post,
            $"/api/public/companion/activities/{Uri.EscapeDataString(activityId)}/read",
            new { }, deviceToken, cancellationToken).ConfigureAwait(false);

    public async Task MarkAllReadAsync(string deviceToken, CancellationToken cancellationToken) =>
        await SendAsync<object?>(HttpMethod.Post, "/api/public/companion/activities/read-all",
            new { }, deviceToken, cancellationToken).ConfigureAwait(false);

    private async Task<T> SendAsync<T>(HttpMethod method, string path, object? body, string? token,
        CancellationToken cancellationToken)
    {
        using HttpRequestMessage request = new(method, path);
        if (body is not null) request.Content = JsonContent.Create(body, options: Json);
        if (!string.IsNullOrWhiteSpace(token)) {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        }
        using HttpResponseMessage response = await _http.SendAsync(request, cancellationToken).ConfigureAwait(false);
        ApiResponse<T>? envelope = null;
        try {
            envelope = await response.Content.ReadFromJsonAsync<ApiResponse<T>>(Json, cancellationToken)
                .ConfigureAwait(false);
        } catch (JsonException) {
            // 代理错误页或空响应统一转成可重试错误，绝不把 HTML 塞进桌宠气泡。
        }
        string message = envelope?.Message ?? $"WAO 返回 HTTP {(int)response.StatusCode}";
        if (response.StatusCode == HttpStatusCode.Unauthorized) throw new CompanionUnauthorizedException(message);
        if (!response.IsSuccessStatusCode || envelope is null || envelope.Code != 0) {
            throw new CompanionApiException(message, (int)response.StatusCode);
        }
        if (envelope.Data is null && typeof(T) != typeof(object)) {
            throw new CompanionApiException("WAO 返回了空数据", (int)response.StatusCode);
        }
        return envelope.Data!;
    }
}
