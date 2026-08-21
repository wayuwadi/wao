using Wao.Companion.Windows;
using Xunit;

namespace Wao.Companion.Windows.Tests;

public class LocalSettingsStoreTests
{
    [Fact]
    public void SavesOnlyDpapiCiphertextAndRestoresConfiguration()
    {
        string folder = Path.Combine(Path.GetTempPath(), "wao-companion-tests", Guid.NewGuid().ToString("N"));
        try {
            var store = new LocalSettingsStore(folder);
            const string token = "plain-device-token-must-never-be-persisted";
            var settings = new CompanionSettings {
                DeviceId = "device-id",
                ProtectedToken = store.ProtectToken(token),
                PetLeft = 123,
                PetTop = 456,
                PetVisible = false,
                StartWithWindows = true,
            };

            store.Save(settings);
            string json = File.ReadAllText(Path.Combine(folder, "settings.json"));
            CompanionSettings restored = store.Load();

            Assert.DoesNotContain(token, json, StringComparison.Ordinal);
            Assert.Equal(token, store.UnprotectToken(restored.ProtectedToken));
            Assert.Equal("device-id", restored.DeviceId);
            Assert.Equal(123, restored.PetLeft);
            Assert.False(restored.PetVisible);
            Assert.True(restored.StartWithWindows);
        } finally {
            if (Directory.Exists(folder)) Directory.Delete(folder, true);
        }
    }
}
