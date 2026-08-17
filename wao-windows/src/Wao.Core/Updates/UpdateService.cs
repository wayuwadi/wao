using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Wao.Core.Updates;

public sealed partial class UpdateService
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = true
    };

    private readonly HttpClient _httpClient;
    private readonly Uri _manifestUri;

    public UpdateService(HttpClient httpClient, Uri manifestUri)
    {
        _httpClient = httpClient;
        _manifestUri = manifestUri;
    }

    public async Task<UpdateCheckResult> CheckAsync(
        Version currentVersion,
        CancellationToken cancellationToken = default)
    {
        using var response = await _httpClient.GetAsync(_manifestUri, cancellationToken).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        var manifest = await JsonSerializer.DeserializeAsync<UpdateManifest>(
            stream,
            JsonOptions,
            cancellationToken).ConfigureAwait(false)
            ?? throw new InvalidDataException("更新清单为空");

        if (!Version.TryParse(manifest.Version, out var latestVersion))
        {
            throw new InvalidDataException("更新清单中的版本号无效");
        }
        if (!Uri.TryCreate(manifest.Url, UriKind.Absolute, out var packageUri) ||
            packageUri.Scheme != Uri.UriSchemeHttps)
        {
            throw new InvalidDataException("更新包必须使用 HTTPS 绝对地址");
        }
        if (!Sha256Pattern().IsMatch(manifest.Sha256))
        {
            throw new InvalidDataException("更新清单中的 SHA-256 无效");
        }

        return new UpdateCheckResult(latestVersion > currentVersion, currentVersion, latestVersion, manifest);
    }

    public async Task DownloadAndVerifyAsync(
        UpdateManifest manifest,
        string destinationPath,
        CancellationToken cancellationToken = default)
    {
        using var response = await _httpClient.GetAsync(
            manifest.Url,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();

        await using (var source = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false))
        await using (var destination = new FileStream(
            destinationPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            81920,
            FileOptions.Asynchronous | FileOptions.SequentialScan))
        {
            await source.CopyToAsync(destination, cancellationToken).ConfigureAwait(false);
        }

        await using var package = File.OpenRead(destinationPath);
        var actual = Convert.ToHexString(await SHA256.HashDataAsync(package, cancellationToken)).ToLowerInvariant();
        if (!CryptographicOperations.FixedTimeEquals(
            Convert.FromHexString(actual),
            Convert.FromHexString(manifest.Sha256)))
        {
            File.Delete(destinationPath);
            throw new InvalidDataException("更新包校验失败，已删除下载文件");
        }
    }

    [GeneratedRegex("^[0-9a-fA-F]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex Sha256Pattern();
}
