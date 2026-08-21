using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Wao.Companion.Windows;

internal sealed class CompanionSettings
{
    public string DeviceId { get; set; } = Guid.NewGuid().ToString("N");
    public string? ProtectedToken { get; set; }
    public double? PetLeft { get; set; }
    public double? PetTop { get; set; }
    public bool PetVisible { get; set; } = true;
    public bool StartWithWindows { get; set; }
}

internal sealed class LocalSettingsStore
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };
    private readonly string _folder;
    private string SettingsPath => Path.Combine(_folder, "settings.json");

    internal LocalSettingsStore(string? folder = null)
    {
        _folder = folder ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WAO", "Companion");
    }

    public CompanionSettings Load()
    {
        try {
            if (File.Exists(SettingsPath)) {
                CompanionSettings? value = JsonSerializer.Deserialize<CompanionSettings>(File.ReadAllText(SettingsPath), Json);
                if (value is not null) {
                    if (string.IsNullOrWhiteSpace(value.DeviceId)) value.DeviceId = Guid.NewGuid().ToString("N");
                    return value;
                }
            }
        } catch (JsonException) {
            // 损坏设置不阻止桌宠启动；原文件不包含业务数据或密码。
        }
        return new CompanionSettings();
    }

    public void Save(CompanionSettings settings)
    {
        Directory.CreateDirectory(_folder);
        string temporary = SettingsPath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(settings, Json), Encoding.UTF8);
        File.Move(temporary, SettingsPath, true);
    }

    public string? UnprotectToken(string? protectedToken)
    {
        if (string.IsNullOrWhiteSpace(protectedToken)) return null;
        try {
            byte[] cipher = Convert.FromBase64String(protectedToken);
            byte[] plain = ProtectedData.Unprotect(cipher, null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(plain);
        } catch (CryptographicException) {
            return null;
        } catch (FormatException) {
            return null;
        }
    }

    public string ProtectToken(string token)
    {
        byte[] cipher = ProtectedData.Protect(Encoding.UTF8.GetBytes(token), null,
            DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(cipher);
    }
}
