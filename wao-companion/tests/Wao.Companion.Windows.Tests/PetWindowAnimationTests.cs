using Wao.Companion.Windows;
using Xunit;

namespace Wao.Companion.Windows.Tests;

public class PetWindowAnimationTests
{
    [Theory]
    [InlineData("IDLE", new[] { 0, 1 })]
    [InlineData("RUNNING", new[] { 4, 5 })]
    [InlineData("NEEDS_INPUT", new[] { 11, 0 })]
    [InlineData("READY", new[] { 10, 1 })]
    [InlineData("BLOCKED", new[] { 8, 0 })]
    public void MapsBusinessStateToSpriteFrames(string state, int[] expected)
    {
        Assert.Equal(expected, PetWindow.FramesForState(state));
    }
}
