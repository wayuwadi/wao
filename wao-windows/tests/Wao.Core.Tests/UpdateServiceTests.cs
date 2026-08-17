using System.Net;
using System.Security.Cryptography;
using System.Text;
using Wao.Core.Updates;

namespace Wao.Core.Tests;

public sealed class UpdateServiceTests
{
    [Fact]
    public async Task FindsNewerHttpsRelease()
    {
        var json = """{"version":"1.0.42.0","url":"https://wao.example/downloads/WAO.zip","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","publishedAt":"2026-08-17T12:00:00Z"}""";
        var service = CreateService(_ => Json(json));

        var result = await service.CheckAsync(new Version(1, 0, 41, 0));

        Assert.True(result.IsAvailable);
        Assert.Equal(new Version(1, 0, 42, 0), result.LatestVersion);
    }

    [Fact]
    public async Task RejectsInsecurePackageUrl()
    {
        var json = """{"version":"1.0.42.0","url":"http://wao.example/WAO.zip","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","publishedAt":"2026-08-17T12:00:00Z"}""";
        var service = CreateService(_ => Json(json));

        await Assert.ThrowsAsync<InvalidDataException>(
            () => service.CheckAsync(new Version(1, 0, 41, 0)));
    }

    [Fact]
    public async Task DownloadsOnlyPackageMatchingSha256()
    {
        var package = Encoding.UTF8.GetBytes("real package");
        var sha = Convert.ToHexString(SHA256.HashData(package)).ToLowerInvariant();
        var manifest = new UpdateManifest("1.0.42.0", "https://wao.example/WAO.zip", sha, DateTimeOffset.UtcNow);
        var service = CreateService(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ByteArrayContent(package)
        });
        var destination = Path.Combine(Path.GetTempPath(), $"wao-update-{Guid.NewGuid():N}.zip");

        try
        {
            await service.DownloadAndVerifyAsync(manifest, destination);
            Assert.Equal(package, await File.ReadAllBytesAsync(destination));
        }
        finally
        {
            File.Delete(destination);
        }
    }

    [Fact]
    public async Task DeletesTamperedPackage()
    {
        var manifest = new UpdateManifest(
            "1.0.42.0",
            "https://wao.example/WAO.zip",
            new string('a', 64),
            DateTimeOffset.UtcNow);
        var service = CreateService(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent("tampered")
        });
        var destination = Path.Combine(Path.GetTempPath(), $"wao-update-{Guid.NewGuid():N}.zip");

        await Assert.ThrowsAsync<InvalidDataException>(
            () => service.DownloadAndVerifyAsync(manifest, destination));
        Assert.False(File.Exists(destination));
    }

    private static UpdateService CreateService(Func<HttpRequestMessage, HttpResponseMessage> responder) =>
        new(new HttpClient(new StubHandler(responder)), new Uri("https://wao.example/latest.json"));

    private static HttpResponseMessage Json(string json) => new(HttpStatusCode.OK)
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
