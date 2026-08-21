using Wao.Companion.Core;
using Xunit;

namespace Wao.Companion.Core.Tests;

public class CompanionStateTests
{
    [Fact]
    public void AggregateUsesNeedsInputBlockedReadyRunningPriority()
    {
        var items = new[] {
            Activity("RUNNING", false),
            Activity("READY", true),
            Activity("BLOCKED", true),
            Activity("NEEDS_INPUT", true),
        };

        Assert.Equal("NEEDS_INPUT", CompanionState.Aggregate(items));
        Assert.Equal("BLOCKED", CompanionState.Aggregate(items.Where(item => item.Status != "NEEDS_INPUT")));
        Assert.Equal("READY", CompanionState.Aggregate(items.Where(item => item.Status is "READY" or "RUNNING")));
        Assert.Equal("RUNNING", CompanionState.Aggregate(items.Where(item => item.Status == "RUNNING")));
        Assert.Equal("IDLE", CompanionState.Aggregate([]));
    }

    [Fact]
    public void ReadFinishedItemsDoNotOverrideRunning()
    {
        Assert.Equal("RUNNING", CompanionState.Aggregate([
            Activity("BLOCKED", false), Activity("READY", false), Activity("RUNNING", false),
        ]));
    }

    [Fact]
    public void PollDelayBacksOffAndUsesThirtySecondsWhenHidden()
    {
        Assert.Equal(TimeSpan.FromSeconds(5), CompanionState.PollDelay(true, 0));
        Assert.Equal(TimeSpan.FromSeconds(8), CompanionState.PollDelay(true, 1));
        Assert.Equal(TimeSpan.FromSeconds(15), CompanionState.PollDelay(true, 2));
        Assert.Equal(TimeSpan.FromSeconds(30), CompanionState.PollDelay(true, 3));
        Assert.Equal(TimeSpan.FromSeconds(30), CompanionState.PollDelay(false, 0));
    }

    [Fact]
    public void RestorePositionKeepsVisibleCoordinatesAndRecoversMissingMonitor()
    {
        var virtualArea = new CompanionBounds(-1920, 0, 3840, 1080);
        var primaryWork = new CompanionBounds(0, 0, 1920, 1040);

        Assert.Equal(new CompanionPoint(-500, 200),
            CompanionState.RestorePosition(-500, 200, 230, 260, virtualArea, primaryWork));
        Assert.Equal(new CompanionPoint(-1920, 0),
            CompanionState.RestorePosition(-2000, -200, 230, 260, virtualArea, primaryWork));
        Assert.Equal(new CompanionPoint(1672, 762),
            CompanionState.RestorePosition(null, null, 230, 260, virtualArea, primaryWork));
    }

    private static CompanionActivity Activity(string status, bool unread) =>
        new("run", status, "任务", "WAO", "状态", null, "https://wao.example", unread);
}
