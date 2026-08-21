using System.IO;
using System.Runtime.InteropServices;

namespace Wao.Companion.Windows;

internal static class StartupShortcutService
{
    private static string ShortcutPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.Startup), "WAO Companion.lnk");

    public static bool IsEnabled => File.Exists(ShortcutPath);

    public static void SetEnabled(bool enabled)
    {
        if (!enabled) {
            if (File.Exists(ShortcutPath)) File.Delete(ShortcutPath);
            return;
        }
        string executable = Environment.ProcessPath
            ?? throw new InvalidOperationException("无法确定桌宠程序路径");
        Type shellType = Type.GetTypeFromProgID("WScript.Shell")
            ?? throw new InvalidOperationException("Windows 快捷方式服务不可用");
        object shell = Activator.CreateInstance(shellType)!;
        object? shortcut = null;
        try {
            shortcut = shellType.InvokeMember("CreateShortcut",
                System.Reflection.BindingFlags.InvokeMethod, null, shell, [ShortcutPath]);
            Type shortcutType = shortcut!.GetType();
            shortcutType.InvokeMember("TargetPath", System.Reflection.BindingFlags.SetProperty,
                null, shortcut, [executable]);
            shortcutType.InvokeMember("WorkingDirectory", System.Reflection.BindingFlags.SetProperty,
                null, shortcut, [Path.GetDirectoryName(executable)!]);
            shortcutType.InvokeMember("Description", System.Reflection.BindingFlags.SetProperty,
                null, shortcut, ["WAO Windows 桌面宠物"]);
            shortcutType.InvokeMember("Save", System.Reflection.BindingFlags.InvokeMethod,
                null, shortcut, null);
        } finally {
            if (shortcut is not null && Marshal.IsComObject(shortcut)) Marshal.FinalReleaseComObject(shortcut);
            if (Marshal.IsComObject(shell)) Marshal.FinalReleaseComObject(shell);
        }
    }
}
