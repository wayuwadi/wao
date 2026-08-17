using System.Net;
using System.Text;
using Wao.Core.Api;

namespace Wao.Core.Tests;

public sealed class ApiClientTests
{
    [Fact]
    public async Task DecodesProjectEnvelopeFromRealContract()
    {
        var client = CreateClient(request =>
        {
            Assert.Equal("/api/projects", request.RequestUri?.AbsolutePath);
            return Json(HttpStatusCode.OK,
                """{"code":0,"message":"success","data":[{"id":"p1","name":"原生客户端","status":"进行中","progress":35,"goal":"实机可用"}],"timestamp":1}""");
        });

        var projects = await client.GetAsync<IReadOnlyList<Project>>("/api/projects");

        Assert.Equal("原生客户端", Assert.Single(projects).Name);
    }

    [Fact]
    public async Task EncodesLoginWithoutPersistingPassword()
    {
        var client = CreateClient(request =>
        {
            Assert.Equal(HttpMethod.Post, request.Method);
            var body = request.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
            Assert.Contains("\"email\":\"user@example.com\"", body);
            Assert.Contains("\"password\":\"secret123\"", body);
            return Json(HttpStatusCode.OK,
                """{"code":0,"message":"success","data":{"accountId":"a1","member":{"id":"m1","name":"测试用户","initial":"测","color":"#4358D6","role":"成员"}},"timestamp":1}""");
        });

        var session = await client.PostAsync<EmailLoginRequest, AuthSession>(
            "/api/auth/email/login",
            new("user@example.com", "secret123"));

        Assert.Equal("测试用户", session.Member.Name);
    }

    [Fact]
    public async Task SurfacesBusinessFailureMessage()
    {
        var client = CreateClient(_ => Json(HttpStatusCode.BadRequest,
            """{"code":40000,"message":"项目未启用 AI Run","data":null,"timestamp":1}"""));

        var error = await Assert.ThrowsAsync<WaoApiException>(
            () => client.GetAsync<IReadOnlyList<AiRun>>("/api/ai/runs"));

        Assert.Equal("项目未启用 AI Run", error.Message);
    }

    [Fact]
    public async Task MapsUnauthorizedToSessionFailure()
    {
        var client = CreateClient(_ => Json(HttpStatusCode.Unauthorized,
            """{"code":40100,"message":"未登录","data":null}"""));

        await Assert.ThrowsAsync<WaoUnauthorizedException>(
            () => client.GetAsync<AuthSession>("/api/auth/session"));
    }

    private static WaoApiClient CreateClient(Func<HttpRequestMessage, HttpResponseMessage> responder)
    {
        var http = new HttpClient(new StubHandler(responder))
        {
            BaseAddress = new Uri("https://wao.example"),
            Timeout = TimeSpan.FromSeconds(2)
        };
        return new WaoApiClient(http);
    }

    private static HttpResponseMessage Json(HttpStatusCode status, string json) => new(status)
    {
        Content = new StringContent(json, Encoding.UTF8, "application/json")
    };

    private sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) => Task.FromResult(responder(request));
    }
}
