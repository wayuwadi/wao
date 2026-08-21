namespace Wao.Companion.Core;

public sealed record ApiResponse<T>(int Code, string? Message, T? Data);

public sealed record CreatePairingRequest(string DeviceId, string DeviceName, string Platform);

public sealed record PairingCreated(
    string PairingId,
    string UserCode,
    string PollSecret,
    string VerificationUrl,
    string ExpiresAt);

public sealed record PairingPoll(string Status, string? DeviceId, string? DeviceToken, string ExpiresAt);

public sealed record CompanionActivity(
    string Id,
    string Status,
    string Title,
    string ProjectName,
    string Message,
    string? OccurredAt,
    string DeepLink,
    bool Unread);

public sealed record CompanionSnapshot(
    string Status,
    int UnreadCount,
    string ServerTime,
    IReadOnlyList<CompanionActivity> Activities);

public class CompanionApiException : Exception
{
    public CompanionApiException(string message, int statusCode = 0) : base(message) => StatusCode = statusCode;
    public int StatusCode { get; }
}

public sealed class CompanionUnauthorizedException : CompanionApiException
{
    public CompanionUnauthorizedException(string message) : base(message, 401) { }
}
