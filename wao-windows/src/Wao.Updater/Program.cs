using System.Diagnostics;
using System.IO;

namespace Wao.Updater;

internal static class Program
{
    private const int WaitTimeoutMilliseconds = 60_000;

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            var options = Options.Parse(args);
            WaitForApplication(options.ProcessId);
            ReplaceApplication(options.PackageDirectory, options.TargetDirectory);
            Process.Start(new ProcessStartInfo
            {
                FileName = Path.Combine(options.TargetDirectory, options.LaunchFile),
                WorkingDirectory = options.TargetDirectory,
                UseShellExecute = true
            });
            ScheduleTemporaryCleanup(options.PackageDirectory);
            return 0;
        }
        catch (Exception exception)
        {
            WriteFailure(exception);
            return 1;
        }
    }

    private static void WaitForApplication(int processId)
    {
        try
        {
            using var process = Process.GetProcessById(processId);
            if (!process.WaitForExit(WaitTimeoutMilliseconds))
            {
                throw new TimeoutException("等待 WAO 退出超时，更新未执行");
            }
        }
        catch (ArgumentException)
        {
            // 主程序已经退出，可以继续更新。
        }
    }

    internal static void ReplaceApplication(string packageDirectory, string targetDirectory)
    {
        var package = Path.GetFullPath(packageDirectory);
        var target = Path.GetFullPath(targetDirectory);
        if (!Directory.Exists(package) || !File.Exists(Path.Combine(package, "WAO.exe")))
        {
            throw new InvalidDataException("更新包缺少 WAO.exe");
        }
        if (string.Equals(package, target, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("更新包目录不能与安装目录相同");
        }

        var backup = Path.Combine(Path.GetTempPath(), $"wao-backup-{Guid.NewGuid():N}");
        Directory.CreateDirectory(backup);
        var backupCanBeDeleted = false;
        try
        {
            foreach (var file in Directory.EnumerateFiles(target, "*", SearchOption.TopDirectoryOnly))
            {
                File.Move(file, Path.Combine(backup, Path.GetFileName(file)), true);
            }
            foreach (var directory in Directory.EnumerateDirectories(target, "*", SearchOption.TopDirectoryOnly))
            {
                Directory.Move(directory, Path.Combine(backup, Path.GetFileName(directory)));
            }

            CopyDirectory(package, target);
            backupCanBeDeleted = true;
        }
        catch (Exception updateException)
        {
            try
            {
                RestoreBackup(backup, target);
                backupCanBeDeleted = true;
            }
            catch (Exception restoreException)
            {
                throw new AggregateException(
                    $"更新和回滚均失败，上一版文件保留在 {backup}",
                    updateException,
                    restoreException);
            }
            throw;
        }
        finally
        {
            if (backupCanBeDeleted) TryDeleteDirectory(backup);
            TryDeleteDirectory(package);
        }
    }

    private static void CopyDirectory(string source, string destination)
    {
        Directory.CreateDirectory(destination);
        foreach (var directory in Directory.EnumerateDirectories(source, "*", SearchOption.AllDirectories))
        {
            Directory.CreateDirectory(Path.Combine(destination, Path.GetRelativePath(source, directory)));
        }
        foreach (var file in Directory.EnumerateFiles(source, "*", SearchOption.AllDirectories))
        {
            var destinationFile = Path.Combine(destination, Path.GetRelativePath(source, file));
            File.Copy(file, destinationFile, true);
        }
    }

    private static void RestoreBackup(string backup, string target)
    {
        if (!Directory.Exists(backup)) return;
        foreach (var file in Directory.EnumerateFiles(target, "*", SearchOption.TopDirectoryOnly))
        {
            TryDeleteFile(file);
        }
        foreach (var directory in Directory.EnumerateDirectories(target, "*", SearchOption.TopDirectoryOnly))
        {
            TryDeleteDirectory(directory);
        }
        CopyDirectory(backup, target);
    }

    private static void WriteFailure(Exception exception)
    {
        var logDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "WAO",
            "logs");
        Directory.CreateDirectory(logDirectory);
        File.WriteAllText(
            Path.Combine(logDirectory, "update-error.log"),
            $"{DateTimeOffset.Now:O}{Environment.NewLine}{exception}");
    }

    private static void ScheduleTemporaryCleanup(string packageDirectory)
    {
        var root = Directory.GetParent(Path.GetFullPath(packageDirectory))?.FullName;
        if (root is null ||
            !root.StartsWith(Path.GetTempPath(), StringComparison.OrdinalIgnoreCase) ||
            !Path.GetFileName(root).StartsWith("wao-update-", StringComparison.OrdinalIgnoreCase))
        {
            return;
        }
        Process.Start(new ProcessStartInfo
        {
            FileName = "cmd.exe",
            Arguments = $"/d /c timeout /t 3 /nobreak >nul & rmdir /s /q \"{root}\"",
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden
        });
    }

    private static void TryDeleteDirectory(string path)
    {
        try { if (Directory.Exists(path)) Directory.Delete(path, true); } catch { }
    }

    private static void TryDeleteFile(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    private sealed record Options(string PackageDirectory, string TargetDirectory, int ProcessId, string LaunchFile)
    {
        public static Options Parse(string[] args)
        {
            var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            for (var index = 0; index + 1 < args.Length; index += 2)
            {
                values[args[index]] = args[index + 1];
            }
            if (!values.TryGetValue("--package", out var package) ||
                !values.TryGetValue("--target", out var target) ||
                !values.TryGetValue("--pid", out var rawProcessId) ||
                !int.TryParse(rawProcessId, out var processId))
            {
                throw new ArgumentException("更新参数不完整");
            }
            var launch = values.GetValueOrDefault("--launch", "WAO.exe");
            if (Path.GetFileName(launch) != launch)
            {
                throw new ArgumentException("启动文件名无效");
            }
            return new(package, target, processId, launch);
        }
    }
}
