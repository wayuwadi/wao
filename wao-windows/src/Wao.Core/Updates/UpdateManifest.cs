namespace Wao.Core.Updates;

public sealed record UpdateManifest(string Version, string Url, string Sha256, DateTimeOffset PublishedAt);

public sealed record UpdateCheckResult(bool IsAvailable, Version CurrentVersion, Version LatestVersion, UpdateManifest Manifest);
