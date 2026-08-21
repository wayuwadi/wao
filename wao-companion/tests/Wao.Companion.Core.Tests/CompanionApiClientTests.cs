using System.Net;
using System.Text;
using Wao.Companion.Core;
using Xunit;

namespace Wao.Companion.Core.Tests;

public class CompanionApiClientTests
{
    [Fact]
    public async Task ActivitiesUseDeviceBearerAndDecodeEnvelope()
    {
        HttpRequestMessage? captured = null;
        using var http = new HttpClient(new StubHandler(request => {
            captured = request;
            return Json(HttpStatusCode.OK,
                """{"code":0,"message":"ok","data":{"status":"IDLE","unreadCount":0,"serverTime":"now","activities":[]}}""");
        })) { BaseAddress = new Uri("https://wao.example") };

        var snapshot = await new CompanionApiClient(http).GetActivitiesAsync("device-secret", default);

        Assert.Equal("IDLE", snapshot.Status);
        Assert.Equal("Bearer", captured!.Headers.Authorization!.Scheme);
        Assert.Equal("device-secret", captured.Headers.Authorization.Parameter);
        Assert.Equal("/api/public/companion/activities", captured.RequestUri!.AbsolutePath);
    }

    [Fact]
    public async Task UnauthorizedHasDedicatedExceptionAndDoesNotExposeHtml()
    {
        using var http = new HttpClient(new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.Unauthorized) {
            Content = new StringContent("<html>proxy error</html>", Encoding.UTF8, "text/html"),
        })) { BaseAddress = new Uri("https://wao.example") };

        var error = await Assert.ThrowsAsync<CompanionUnauthorizedException>(() =>
            new CompanionApiClient(http).GetActivitiesAsync("revoked-token", default));

        Assert.DoesNotContain("html", error.Message, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(401, error.StatusCode);
    }

    [Fact]
    public async Task BusinessErrorEnvelopeBecomesApiException()
    {
        using var http = new HttpClient(new StubHandler(_ => Json(HttpStatusCode.Conflict,
            """{"code":409,"message":"配对码已使用","data":null}"""))) {
            BaseAddress = new Uri("https://wao.example"),
        };

        var error = await Assert.ThrowsAsync<CompanionApiException>(() =>
            new CompanionApiClient(http).CreatePairingAsync(new("id", "PC", "windows-x64"), default));

        Assert.Equal("配对码已使用", error.Message);
        Assert.Equal(409, error.StatusCode);
    }

    private static HttpResponseMessage Json(HttpStatusCode status, string value) => new(status)
    {
        Content = new StringContent(value, Encoding.UTF8, "application/json"),
    };

    private sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> send) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,
            CancellationToken cancellationToken) => Task.FromResult(send(request));
    }
}
