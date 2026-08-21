using System.Diagnostics;
using System.Drawing;
using System.Net.Http;
using System.Windows;
using Forms = System.Windows.Forms;
using WpfApplication = System.Windows.Application;
using Wao.Companion.Core;

namespace Wao.Companion.Windows;

internal sealed class AppController : IDisposable
{
    private static readonly Uri WaoBaseUri = new("https://wao.wayuwa.cn");
    private readonly LocalSettingsStore _store = new();
    private readonly CompanionSettings _settings;
    private readonly HttpClient _http;
    private readonly CompanionApiClient _api;
    private readonly PairingWindow _pairingWindow = new();
    private readonly Forms.NotifyIcon _notifyIcon;
    private readonly Forms.ToolStripMenuItem _visibilityMenu;
    private readonly Forms.ToolStripMenuItem _startupMenu;
    private readonly SemaphoreSlim _refreshGate = new(1, 1);
    private PetWindow? _pet;
    private ActivityTrayWindow? _tray;
    private CompanionSnapshot? _latestSnapshot;
    private CancellationTokenSource? _pairingCancellation;
    private CancellationTokenSource? _activityCancellation;
    private string? _deviceToken;
    private bool _exiting;

    public AppController()
    {
        _settings = _store.Load();
        _deviceToken = _store.UnprotectToken(_settings.ProtectedToken);
        if (_deviceToken is null && _settings.ProtectedToken is not null) {
            _settings.ProtectedToken = null;
            _store.Save(_settings);
        }
        _http = new HttpClient
        {
            BaseAddress = WaoBaseUri,
            Timeout = TimeSpan.FromSeconds(15),
        };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("WAO-Companion/1.0 (Windows)");
        _api = new CompanionApiClient(_http);

        _visibilityMenu = new Forms.ToolStripMenuItem("唤醒桌宠", null, (_, _) => Dispatch(TogglePetVisibility));
        _startupMenu = new Forms.ToolStripMenuItem("开机启动") { CheckOnClick = true };
        _startupMenu.Click += (_, _) => Dispatch(() => SetStartup(_startupMenu.Checked));
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("打开 WAO", null, (_, _) => Dispatch(() => OpenUrl(WaoBaseUri.ToString())));
        menu.Items.Add("立即刷新", null, (_, _) => Dispatch(() => _ = RefreshOnceAsync()));
        menu.Items.Add(_visibilityMenu);
        menu.Items.Add("连接 / 重新配对", null, (_, _) => Dispatch(BeginRePair));
        menu.Items.Add(_startupMenu);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("退出", null, (_, _) => Dispatch(Exit));
        _notifyIcon = new Forms.NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "WAO Companion",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _notifyIcon.DoubleClick += (_, _) => Dispatch(ShowPet);
        _pairingWindow.RetryRequested += (_, _) => _ = BeginPairingAsync();
    }

    internal async Task StartAsync()
    {
        bool actualStartup = StartupShortcutService.IsEnabled;
        if (_settings.StartWithWindows != actualStartup) {
            _settings.StartWithWindows = actualStartup;
            _store.Save(_settings);
        }
        _startupMenu.Checked = actualStartup;
        if (_deviceToken is null) {
            await BeginPairingAsync();
            return;
        }
        EnsurePetWindows();
        if (_settings.PetVisible) ShowPet(); else UpdateVisibilityMenus();
        StartActivityLoop();
    }

    private async Task BeginPairingAsync()
    {
        _pairingCancellation?.Cancel();
        _pairingCancellation?.Dispose();
        _pairingCancellation = new CancellationTokenSource();
        CancellationToken cancellationToken = _pairingCancellation.Token;
        _pairingWindow.ShowCreating();
        if (!_pairingWindow.IsVisible) _pairingWindow.Show();
        _pairingWindow.Activate();
        try {
            PairingCreated pairing = await _api.CreatePairingAsync(new CreatePairingRequest(
                _settings.DeviceId, Environment.MachineName, "windows-x64"), cancellationToken);
            _pairingWindow.ShowCode(pairing);
            OpenUrl(pairing.VerificationUrl);
            while (!cancellationToken.IsCancellationRequested) {
                await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken);
                PairingPoll result = await _api.PollPairingAsync(pairing.PairingId, pairing.PollSecret,
                    cancellationToken);
                if (result.Status == "APPROVED") {
                    _pairingWindow.ShowApproved();
                    continue;
                }
                if (result.Status == "EXPIRED") {
                    _pairingWindow.ShowError("配对码已经过期，请重新生成。");
                    return;
                }
                if (result.Status != "PAIRED") continue;
                if (string.IsNullOrWhiteSpace(result.DeviceToken)) {
                    _pairingWindow.ShowError("设备令牌已经领取，请重新生成配对码。未保存任何无效凭据。");
                    return;
                }
                _deviceToken = result.DeviceToken;
                _settings.ProtectedToken = _store.ProtectToken(result.DeviceToken);
                _settings.PetVisible = true;
                _store.Save(_settings);
                _pairingWindow.Hide();
                EnsurePetWindows();
                ShowPet();
                StartActivityLoop();
                return;
            }
        } catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) {
            // 新一轮配对或退出会取消旧轮询。
        } catch (Exception exception) {
            _pairingWindow.ShowError(Friendly(exception));
        }
    }

    private void EnsurePetWindows()
    {
        if (_pet is null) {
            _pet = new PetWindow();
            _pet.RestorePosition(_settings.PetLeft, _settings.PetTop);
            _pet.SetStartupChecked(_settings.StartWithWindows);
            _pet.ToggleTrayRequested += (_, _) => ToggleTray();
            _pet.OpenWaoRequested += (_, _) => OpenUrl(WaoBaseUri.ToString());
            _pet.RefreshRequested += (_, _) => _ = RefreshOnceAsync();
            _pet.HideRequested += (_, _) => HidePet();
            _pet.StartupToggled += (_, enabled) => SetStartup(enabled);
            _pet.RePairRequested += (_, _) => BeginRePair();
            _pet.ExitRequested += (_, _) => Exit();
            _pet.PositionCommitted += (_, _) => SavePetPosition();
        }
        if (_tray is null) {
            _tray = new ActivityTrayWindow();
            _tray.ActivitySelected += (_, activity) => _ = OpenActivityAsync(activity);
            _tray.ReadAllRequested += (_, _) => _ = MarkAllReadAsync();
        }
    }

    private void StartActivityLoop()
    {
        _activityCancellation?.Cancel();
        _activityCancellation?.Dispose();
        _activityCancellation = new CancellationTokenSource();
        _ = ActivityLoopAsync(_activityCancellation.Token);
    }

    private async Task ActivityLoopAsync(CancellationToken cancellationToken)
    {
        int failures = 0;
        while (!cancellationToken.IsCancellationRequested && _deviceToken is not null) {
            try {
                await RefreshCoreAsync(cancellationToken);
                failures = 0;
            } catch (CompanionUnauthorizedException) {
                await WpfApplication.Current.Dispatcher.InvokeAsync(HandleRevoked);
                return;
            } catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) {
                return;
            } catch (Exception exception) {
                failures++;
                await WpfApplication.Current.Dispatcher.InvokeAsync(() => {
                    EnsurePetWindows();
                    _pet!.SetOffline($"连接 WAO 失败：{Friendly(exception)}");
                    UpdateNotify("离线重试", 0);
                });
            }
            try {
                await Task.Delay(CompanionState.PollDelay(_settings.PetVisible, failures), cancellationToken);
            } catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) {
                return;
            }
        }
    }

    private async Task RefreshCoreAsync(CancellationToken cancellationToken)
    {
        if (_deviceToken is null || !await _refreshGate.WaitAsync(0, cancellationToken)) return;
        try {
            CompanionSnapshot snapshot = await _api.GetActivitiesAsync(_deviceToken, cancellationToken);
            await WpfApplication.Current.Dispatcher.InvokeAsync(() => ApplySnapshot(snapshot));
        } finally {
            _refreshGate.Release();
        }
    }

    private async Task RefreshOnceAsync()
    {
        try {
            await RefreshCoreAsync(CancellationToken.None);
        } catch (CompanionUnauthorizedException) {
            HandleRevoked();
        } catch (Exception exception) {
            EnsurePetWindows();
            _pet!.SetOffline($"连接 WAO 失败：{Friendly(exception)}");
        }
    }

    private void ApplySnapshot(CompanionSnapshot snapshot)
    {
        _latestSnapshot = snapshot;
        EnsurePetWindows();
        string message = PickMessage(snapshot);
        _pet!.SetState(snapshot.Status, snapshot.UnreadCount, message);
        _tray!.SetSnapshot(snapshot);
        UpdateNotify(StatusLabel(snapshot.Status), snapshot.UnreadCount);
    }

    private static string PickMessage(CompanionSnapshot snapshot)
    {
        CompanionActivity? focus = snapshot.Activities.FirstOrDefault(item =>
            item.Status == snapshot.Status && (item.Unread || item.Status == "RUNNING"));
        if (focus is null) return "今天也一起把事情做好。";
        string message = string.IsNullOrWhiteSpace(focus.Message) ? focus.Title : $"{focus.Title}：{focus.Message}";
        return message.Length <= 72 ? message : message[..69] + "...";
    }

    private async Task OpenActivityAsync(CompanionActivity activity)
    {
        try {
            if (_deviceToken is not null) {
                await _api.MarkReadAsync(_deviceToken, activity.Id, CancellationToken.None);
            }
        } catch (CompanionUnauthorizedException) {
            HandleRevoked();
        } catch {
            // 深链仍应打开；已读失败会在下一轮继续提醒，不伪造成功。
        } finally {
            OpenUrl(activity.DeepLink);
            _tray?.Hide();
            _ = RefreshOnceAsync();
        }
    }

    private async Task MarkAllReadAsync()
    {
        if (_deviceToken is null) return;
        try {
            await _api.MarkAllReadAsync(_deviceToken, CancellationToken.None);
            await RefreshOnceAsync();
        } catch (CompanionUnauthorizedException) {
            HandleRevoked();
        } catch (Exception exception) {
            _pet?.SetOffline($"确认已读失败：{Friendly(exception)}");
        }
    }

    private void ToggleTray()
    {
        if (_pet is null || _tray is null) return;
        if (_tray.IsVisible) _tray.Hide();
        else {
            if (_latestSnapshot is not null) _tray.SetSnapshot(_latestSnapshot);
            _tray.ShowBeside(_pet);
        }
    }

    private void ShowPet()
    {
        if (_deviceToken is null) {
            if (!_pairingWindow.IsVisible) _pairingWindow.Show();
            _pairingWindow.Activate();
            return;
        }
        EnsurePetWindows();
        _settings.PetVisible = true;
        _store.Save(_settings);
        _pet!.Show();
        _pet.Activate();
        UpdateVisibilityMenus();
    }

    private void HidePet()
    {
        _tray?.Hide();
        _pet?.Hide();
        _settings.PetVisible = false;
        _store.Save(_settings);
        UpdateVisibilityMenus();
    }

    private void TogglePetVisibility()
    {
        if (_pet?.IsVisible == true) HidePet(); else ShowPet();
    }

    private void SavePetPosition()
    {
        if (_pet is null) return;
        _settings.PetLeft = _pet.Left;
        _settings.PetTop = _pet.Top;
        _store.Save(_settings);
    }

    private void SetStartup(bool enabled)
    {
        try {
            StartupShortcutService.SetEnabled(enabled);
            _settings.StartWithWindows = enabled;
            _store.Save(_settings);
        } catch (Exception exception) {
            System.Windows.MessageBox.Show($"开机启动设置失败：{Friendly(exception)}", "WAO Companion",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        bool actual = StartupShortcutService.IsEnabled;
        _settings.StartWithWindows = actual;
        _startupMenu.Checked = actual;
        _pet?.SetStartupChecked(actual);
    }

    private void BeginRePair()
    {
        _activityCancellation?.Cancel();
        _deviceToken = null;
        _settings.ProtectedToken = null;
        _store.Save(_settings);
        _tray?.Hide();
        _pet?.Hide();
        _ = BeginPairingAsync();
    }

    private void HandleRevoked()
    {
        if (_deviceToken is null) return;
        _deviceToken = null;
        _settings.ProtectedToken = null;
        _store.Save(_settings);
        _activityCancellation?.Cancel();
        _tray?.Hide();
        _pet?.Hide();
        _pairingWindow.ShowError("这台设备已被撤销或登录失效，请重新配对。");
        if (!_pairingWindow.IsVisible) _pairingWindow.Show();
        _pairingWindow.Activate();
        UpdateNotify("需要重新配对", 0);
    }

    private void UpdateVisibilityMenus()
    {
        bool visible = _pet?.IsVisible == true;
        _visibilityMenu.Text = visible ? "隐藏桌宠" : "唤醒桌宠";
    }

    private void UpdateNotify(string state, int unread)
    {
        string suffix = unread > 0 ? $" · {unread} 条未读" : string.Empty;
        string text = $"WAO Companion · {state}{suffix}";
        _notifyIcon.Text = text.Length <= 63 ? text : text[..63];
    }

    private static string StatusLabel(string status) => status switch
    {
        "NEEDS_INPUT" => "需要确认",
        "BLOCKED" => "遇到阻塞",
        "READY" => "已完成",
        "RUNNING" => "运行中",
        _ => "空闲陪伴",
    };

    private static string Friendly(Exception exception) => exception switch
    {
        TaskCanceledException => "请求超时",
        HttpRequestException => "网络不可用",
        CompanionApiException api => api.Message,
        _ => exception.Message,
    };

    private static void OpenUrl(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch { /* 默认浏览器不可用时保持桌宠状态，用户可从托盘重试。 */ }
    }

    private static void Dispatch(Action action)
    {
        if (WpfApplication.Current.Dispatcher.CheckAccess()) action();
        else WpfApplication.Current.Dispatcher.BeginInvoke(action);
    }

    private void Exit()
    {
        _exiting = true;
        _pairingWindow.AllowClose = true;
        WpfApplication.Current.Shutdown();
    }

    public void Dispose()
    {
        _pairingCancellation?.Cancel();
        _activityCancellation?.Cancel();
        _pairingCancellation?.Dispose();
        _activityCancellation?.Dispose();
        _refreshGate.Dispose();
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
        _http.Dispose();
        if (!_exiting) _pairingWindow.AllowClose = true;
        _pairingWindow.Close();
        _tray?.Close();
        _pet?.Close();
    }
}
