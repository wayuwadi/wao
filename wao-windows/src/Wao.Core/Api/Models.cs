using System.Text.Json.Serialization;

namespace Wao.Core.Api;

public sealed record Member(string Id, string Name, string Initial, string Color, string Role);

public sealed record AuthSession(string AccountId, Member Member);

public sealed record EmailLoginRequest(string Email, string Password);

public sealed record Project(string Id, string Name, string Status, int Progress, string Goal);

public sealed record TaskItem(string Id, string Title, string Project, string Status, string Pr, string Due);

public sealed record TaskStatusPatch(string Status);

public sealed record AiRun(
    string Id,
    string TaskTitle,
    string ProjectName,
    string Status,
    string? RunnerName,
    string? ResultSummary,
    string? LatestProgress,
    string CreatedAt);

public sealed record LaunchGoalRequest(string ProjectId, string Goal);

public sealed record AutomationJob(
    string Id,
    string Name,
    string Schedule,
    string Status,
    string Last,
    [property: JsonPropertyName("desc")] string Description,
    string? Handler);

public sealed record EmptyResponse;
