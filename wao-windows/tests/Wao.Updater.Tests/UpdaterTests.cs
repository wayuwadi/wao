using Wao.Updater;

namespace Wao.Updater.Tests;

public sealed class UpdaterTests
{
    [Fact]
    public void ReplacesApplicationAndRemovesRetiredFiles()
    {
        var root = Path.Combine(Path.GetTempPath(), $"wao-updater-test-{Guid.NewGuid():N}");
        var package = Path.Combine(root, "package");
        var target = Path.Combine(root, "target");
        Directory.CreateDirectory(package);
        Directory.CreateDirectory(target);
        File.WriteAllText(Path.Combine(package, "WAO.exe"), "new-version");
        File.WriteAllText(Path.Combine(package, "Wao.Updater.exe"), "new-updater");
        File.WriteAllText(Path.Combine(target, "WAO.exe"), "old-version");
        File.WriteAllText(Path.Combine(target, "retired.dll"), "retired");

        try
        {
            Program.ReplaceApplication(package, target);

            Assert.Equal("new-version", File.ReadAllText(Path.Combine(target, "WAO.exe")));
            Assert.Equal("new-updater", File.ReadAllText(Path.Combine(target, "Wao.Updater.exe")));
            Assert.False(File.Exists(Path.Combine(target, "retired.dll")));
            Assert.False(Directory.Exists(package));
        }
        finally
        {
            if (Directory.Exists(root)) Directory.Delete(root, true);
        }
    }

    [Fact]
    public void RejectsIncompletePackageWithoutTouchingCurrentVersion()
    {
        var root = Path.Combine(Path.GetTempPath(), $"wao-updater-test-{Guid.NewGuid():N}");
        var package = Path.Combine(root, "package");
        var target = Path.Combine(root, "target");
        Directory.CreateDirectory(package);
        Directory.CreateDirectory(target);
        File.WriteAllText(Path.Combine(package, "not-wao.txt"), "invalid");
        File.WriteAllText(Path.Combine(target, "WAO.exe"), "current-version");

        try
        {
            Assert.Throws<InvalidDataException>(() => Program.ReplaceApplication(package, target));
            Assert.Equal("current-version", File.ReadAllText(Path.Combine(target, "WAO.exe")));
        }
        finally
        {
            if (Directory.Exists(root)) Directory.Delete(root, true);
        }
    }
}
