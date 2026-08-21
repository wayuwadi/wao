namespace Wao.Companion.Core;

public static class CompanionState
{
    private static readonly string[] Priority = ["NEEDS_INPUT", "BLOCKED", "READY"];

    public static string Aggregate(IEnumerable<CompanionActivity> activities)
    {
        CompanionActivity[] items = activities.ToArray();
        foreach (string status in Priority) {
            if (items.Any(item => item.Unread && item.Status == status)) return status;
        }
        return items.Any(item => item.Status == "RUNNING") ? "RUNNING" : "IDLE";
    }

    public static TimeSpan PollDelay(bool petVisible, int consecutiveFailures)
    {
        if (!petVisible) return TimeSpan.FromSeconds(30);
        int seconds = consecutiveFailures switch {
            <= 0 => 5,
            1 => 8,
            2 => 15,
            _ => 30,
        };
        return TimeSpan.FromSeconds(seconds);
    }

    public static CompanionPoint RestorePosition(double? requestedLeft, double? requestedTop,
        double width, double height, CompanionBounds virtualArea, CompanionBounds defaultWorkArea)
    {
        bool unavailable = requestedLeft is null || requestedTop is null
            || requestedLeft < virtualArea.Left - width || requestedLeft > virtualArea.Right
            || requestedTop < virtualArea.Top - height || requestedTop > virtualArea.Bottom;
        if (unavailable) {
            return new CompanionPoint(defaultWorkArea.Right - width - 18,
                defaultWorkArea.Bottom - height - 18);
        }
        double left = requestedLeft.GetValueOrDefault();
        double top = requestedTop.GetValueOrDefault();
        return new CompanionPoint(
            Math.Clamp(left, virtualArea.Left, virtualArea.Right - width),
            Math.Clamp(top, virtualArea.Top, virtualArea.Bottom - height));
    }
}

public readonly record struct CompanionPoint(double Left, double Top);

public readonly record struct CompanionBounds(double Left, double Top, double Width, double Height)
{
    public double Right => Left + Width;
    public double Bottom => Top + Height;
}
