using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Threading;
using Wao.Core.Api;
using Wao.Core.Updates;

namespace Wao.Windows;

public partial class MainWindow : Window
{
    private readonly App _app = (App)Application.Current;
    private readonly Version _currentVersion = Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(1, 0, 0, 0);
    private readonly DispatcherTimer _updateTimer = new() { Interval = TimeSpan.FromMinutes(30) };
    private IReadOnlyList<Project> _projects = [];
    private IReadOnlyList<TaskItem> _tasks = [];
    private IReadOnlyList<AiRun> _aiRuns = [];
    private IReadOnlyList<AutomationJob> _automationJobs = [];
    private UpdateCheckResult? _availableUpdate;
    private DesktopPetWindow? _petWindow;
    private string _currentPage = "projects";
    private bool _restoring;

    public MainWindow()
    {
        InitializeComponent();
        VersionText.Text = $"版本 {_currentVersion}";
        _updateTimer.Tick += async (_, _) => await CheckForUpdatesAsync(true);
    }

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        if (_restoring) return;
        _restoring = true;
        LoginPanel.Visibility = Visibility.Collapsed;
        RestoringPanel.Visibility = Visibility.Visible;
        try
        {
            var session = await _app.Api.GetAsync<AuthSession>("/api/auth/session");
            EnterShell(session);
            await LoadProjectsAsync();
        }
        catch (WaoUnauthorizedException)
        {
            ShowLogin();
        }
        catch (Exception exception)
        {
            ShowLogin(exception.Message);
        }
        finally
        {
            _restoring = false;
            _updateTimer.Start();
            _ = CheckForUpdatesAsync(true);
        }
    }

    private async void Login_Click(object sender, RoutedEventArgs e)
    {
        var email = EmailBox.Text.Trim();
        var password = PasswordInput.Password;
        if (string.IsNullOrWhiteSpace(email) || string.IsNullOrEmpty(password))
        {
            LoginErrorText.Text = "请输入邮箱和密码";
            return;
        }

        LoginButton.IsEnabled = false;
        LoginButton.Content = "登录中…";
        LoginErrorText.Text = string.Empty;
        try
        {
            var session = await _app.Api.PostAsync<EmailLoginRequest, AuthSession>(
                "/api/auth/email/login",
                new(email, password));
            PasswordInput.Clear();
            _app.CookieStore.Save(_app.Cookies);
            EnterShell(session);
            await LoadProjectsAsync();
        }
        catch (Exception exception)
        {
            LoginErrorText.Text = exception.Message;
        }
        finally
        {
            LoginButton.IsEnabled = true;
            LoginButton.Content = "登录";
        }
    }

    private void EnterShell(AuthSession session)
    {
        RestoringPanel.Visibility = Visibility.Collapsed;
        LoginPanel.Visibility = Visibility.Collapsed;
        ShellPanel.Visibility = Visibility.Visible;
        MemberNameText.Text = session.Member.Name;
        SetPetVisibility(true);
        ShowPage("projects");
    }

    private void ShowLogin(string? error = null)
    {
        RestoringPanel.Visibility = Visibility.Collapsed;
        ShellPanel.Visibility = Visibility.Collapsed;
        LoginPanel.Visibility = Visibility.Visible;
        LoginErrorText.Text = error ?? string.Empty;
        _petWindow?.Hide();
    }

    private async void Logout_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            await _app.Api.PostAsync<EmptyResponse>("/api/auth/logout");
        }
        catch
        {
            // 服务端会话失效或断网时，本机仍必须退出私有页面。
        }
        foreach (Cookie cookie in _app.Cookies.GetCookies(App.ApiBaseUri)) cookie.Expired = true;
        _app.CookieStore.Clear();
        _projects = [];
        _tasks = [];
        _aiRuns = [];
        _automationJobs = [];
        ProjectList.ItemsSource = null;
        TaskItems.ItemsSource = null;
        AiRunItems.ItemsSource = null;
        AutomationItems.ItemsSource = null;
        ShowLogin();
    }

    private async void Navigate_Click(object sender, RoutedEventArgs e)
    {
        var page = (sender as Button)?.Tag as string ?? "projects";
        ShowPage(page);
        await RefreshCurrentAsync();
    }

    private void ShowPage(string page)
    {
        _currentPage = page;
        ProjectsPanel.Visibility = page == "projects" ? Visibility.Visible : Visibility.Collapsed;
        AiPanel.Visibility = page == "ai" ? Visibility.Visible : Visibility.Collapsed;
        AutomationPanel.Visibility = page == "automation" ? Visibility.Visible : Visibility.Collapsed;
        PageTitleText.Text = page switch
        {
            "ai" => "AI 运行",
            "automation" => "自动化",
            _ => "项目与任务"
        };
        ProjectsNav.Background = page == "projects" ? FindBrush("#DDE1F9") : System.Windows.Media.Brushes.Transparent;
        AiNav.Background = page == "ai" ? FindBrush("#DDE1F9") : System.Windows.Media.Brushes.Transparent;
        AutomationNav.Background = page == "automation" ? FindBrush("#DDE1F9") : System.Windows.Media.Brushes.Transparent;
    }

    internal async Task RefreshCurrentAsync()
    {
        try
        {
            switch (_currentPage)
            {
                case "ai": await LoadAiAsync(); break;
                case "automation": await LoadAutomationAsync(); break;
                default: await LoadProjectsAsync(); break;
            }
        }
        catch (WaoUnauthorizedException)
        {
            _app.CookieStore.Clear();
            ShowLogin("登录已过期，请重新登录");
        }
        catch
        {
            // 各加载方法已经保留现有内容并把可重试错误写入底部状态栏。
        }
    }

    private async Task LoadProjectsAsync()
    {
        SetBusy("正在加载项目与任务…");
        try
        {
            var projectRequest = _app.Api.GetAsync<IReadOnlyList<Project>>("/api/projects");
            var taskRequest = _app.Api.GetAsync<IReadOnlyList<TaskItem>>("/api/tasks");
            await Task.WhenAll(projectRequest, taskRequest);
            _projects = await projectRequest;
            _tasks = await taskRequest;
            ProjectList.ItemsSource = _projects;
            GoalProjectBox.ItemsSource = _projects;
            ProjectsEmptyText.Visibility = _projects.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            if (_projects.Count > 0)
            {
                var selectedId = (ProjectList.SelectedItem as Project)?.Id;
                ProjectList.SelectedItem = _projects.FirstOrDefault(project => project.Id == selectedId) ?? _projects[0];
                GoalProjectBox.SelectedIndex = Math.Max(GoalProjectBox.SelectedIndex, 0);
            }
            else
            {
                TaskItems.ItemsSource = null;
                TasksEmptyText.Text = "暂无项目";
                TasksEmptyText.Visibility = Visibility.Visible;
            }
            SetReady($"已加载 {_projects.Count} 个项目、{_tasks.Count} 条任务");
        }
        catch (Exception exception)
        {
            SetError("项目加载失败", exception);
            throw;
        }
    }

    private void ProjectList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ProjectList.SelectedItem is not Project project) return;
        var tasks = _tasks.Where(task => task.Project == project.Id).ToList();
        TasksHeading.Text = project.Name;
        TaskItems.ItemsSource = tasks;
        TasksEmptyText.Text = "这个项目暂无任务";
        TasksEmptyText.Visibility = tasks.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void ToggleTask_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button || button.DataContext is not TaskItem task) return;
        button.IsEnabled = false;
        var oldContent = button.Content;
        button.Content = "保存中…";
        try
        {
            var nextStatus = task.Status == "已完成" ? "待办" : "已完成";
            var updated = await _app.Api.PatchAsync<TaskStatusPatch, TaskItem>(
                $"/api/tasks/{Uri.EscapeDataString(task.Id)}",
                new(nextStatus));
            _tasks = _tasks.Select(item => item.Id == updated.Id ? updated : item).ToList();
            ProjectList_SelectionChanged(ProjectList, new SelectionChangedEventArgs(
                Selector.SelectionChangedEvent,
                Array.Empty<object>(),
                Array.Empty<object>()));
            SetReady($"“{updated.Title}”已保存为{updated.Status}");
        }
        catch (Exception exception)
        {
            button.Content = oldContent;
            SetError("任务保存失败，可重试", exception);
        }
        finally
        {
            button.IsEnabled = true;
        }
    }

    private async Task LoadAiAsync()
    {
        SetBusy("正在加载 AI 运行…");
        try
        {
            var runRequest = _app.Api.GetAsync<IReadOnlyList<AiRun>>("/api/ai/runs");
            var projectRequest = _app.Api.GetAsync<IReadOnlyList<Project>>("/api/projects");
            await Task.WhenAll(runRequest, projectRequest);
            _aiRuns = await runRequest;
            _projects = await projectRequest;
            GoalProjectBox.ItemsSource = _projects;
            if (_projects.Count > 0 && GoalProjectBox.SelectedIndex < 0) GoalProjectBox.SelectedIndex = 0;
            AiRunItems.ItemsSource = _aiRuns.Select(AiRunDisplay.From).ToList();
            AiEmptyText.Visibility = _aiRuns.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            UpdatePetFromRuns();
            SetReady($"已加载 {_aiRuns.Count} 条 AI 运行");
        }
        catch (Exception exception)
        {
            SetError("AI 运行加载失败", exception);
            throw;
        }
    }

    private async void LaunchGoal_Click(object sender, RoutedEventArgs e)
    {
        if (GoalProjectBox.SelectedItem is not Project project || string.IsNullOrWhiteSpace(GoalTextBox.Text))
        {
            StatusText.Text = "请选择项目并填写目标";
            return;
        }
        LaunchGoalButton.IsEnabled = false;
        LaunchGoalButton.Content = "派发中…";
        try
        {
            var run = await _app.Api.PostAsync<LaunchGoalRequest, AiRun>(
                "/api/ai/goals",
                new(project.Id, GoalTextBox.Text.Trim()));
            GoalTextBox.Clear();
            _aiRuns = new[] { run }.Concat(_aiRuns).ToList();
            AiRunItems.ItemsSource = _aiRuns.Select(AiRunDisplay.From).ToList();
            AiEmptyText.Visibility = Visibility.Collapsed;
            UpdatePetFromRuns();
            SetReady("目标已进入真实 AI 队列");
        }
        catch (Exception exception)
        {
            SetError("目标派发失败，可保留内容后重试", exception);
        }
        finally
        {
            LaunchGoalButton.IsEnabled = true;
            LaunchGoalButton.Content = "派发目标";
        }
    }

    private async Task LoadAutomationAsync()
    {
        SetBusy("正在加载自动化…");
        try
        {
            _automationJobs = await _app.Api.GetAsync<IReadOnlyList<AutomationJob>>("/api/jobs");
            AutomationItems.ItemsSource = _automationJobs;
            AutomationEmptyText.Visibility = _automationJobs.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            SetReady($"已加载 {_automationJobs.Count} 条自动化任务");
        }
        catch (Exception exception)
        {
            SetError("自动化加载失败", exception);
            throw;
        }
    }

    private async void AutomationAction_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button || button.DataContext is not AutomationJob job) return;
        var rawAction = button.Tag as string;
        var action = rawAction == "toggle" ? (job.Status == "已暂停" ? "resume" : "pause") : rawAction;
        if (string.IsNullOrEmpty(action)) return;
        button.IsEnabled = false;
        var oldContent = button.Content;
        button.Content = "保存中…";
        try
        {
            var updated = await _app.Api.PostAsync<AutomationJob>(
                $"/api/jobs/{Uri.EscapeDataString(job.Id)}/{action}");
            _automationJobs = _automationJobs.Select(item => item.Id == updated.Id ? updated : item).ToList();
            AutomationItems.ItemsSource = _automationJobs;
            SetReady($"“{updated.Name}”操作已保存");
        }
        catch (Exception exception)
        {
            button.Content = oldContent;
            SetError("自动化操作失败，可重试", exception);
        }
        finally
        {
            button.IsEnabled = true;
        }
    }

    private async void RefreshProjects_Click(object sender, RoutedEventArgs e) => await RefreshCurrentAsync();
    private async void RefreshAi_Click(object sender, RoutedEventArgs e) => await RefreshCurrentAsync();
    private async void RefreshAutomation_Click(object sender, RoutedEventArgs e) => await RefreshCurrentAsync();

    private void ShowPet_Click(object sender, RoutedEventArgs e) => SetPetVisibility(true);

    internal void SetPetVisibility(bool visible)
    {
        if (!visible)
        {
            _petWindow?.Hide();
            return;
        }
        if (_petWindow is null)
        {
            _petWindow = new DesktopPetWindow(this);
        }
        _petWindow.Show();
        UpdatePetFromRuns();
    }

    internal void BringToFront()
    {
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Show();
        Activate();
        Topmost = true;
        Topmost = false;
        Focus();
    }

    private void UpdatePetFromRuns()
    {
        if (_petWindow is null) return;
        var active = _aiRuns.FirstOrDefault(run => run.Status is "running" or "claimed" or "queued");
        _petWindow.SetMessage(active is null
            ? "今天也一起把事情做好。"
            : $"AI 正在处理：{active.TaskTitle}");
    }

    private async void Update_Click(object sender, RoutedEventArgs e)
    {
        if (_availableUpdate is null)
        {
            await CheckForUpdatesAsync(false);
            return;
        }
        await InstallUpdateAsync(_availableUpdate.Manifest);
    }

    private async Task CheckForUpdatesAsync(bool silent)
    {
        if (!silent) SetBusy("正在检查更新…");
        try
        {
            var result = await _app.Updates.CheckAsync(_currentVersion);
            _availableUpdate = result.IsAvailable ? result : null;
            UpdateDot.Visibility = result.IsAvailable ? Visibility.Visible : Visibility.Collapsed;
            UpdateButton.ToolTip = result.IsAvailable
                ? $"有新版本 {result.LatestVersion}，点击更新"
                : "已是最新版本，点击重新检查";
            if (!silent)
            {
                SetReady(result.IsAvailable
                    ? $"发现新版本 {result.LatestVersion}，再次点击更新图标即可安装"
                    : "当前已是最新版本");
            }
        }
        catch (Exception exception) when (silent)
        {
            Debug.WriteLine($"静默检查更新失败：{exception.Message}");
        }
        catch (Exception exception)
        {
            SetError("检查更新失败", exception);
        }
    }

    private async Task InstallUpdateAsync(UpdateManifest manifest)
    {
        UpdateButton.IsEnabled = false;
        SetBusy($"正在下载 WAO {manifest.Version}…");
        var root = Path.Combine(Path.GetTempPath(), $"wao-update-{Guid.NewGuid():N}");
        var zipPath = Path.Combine(root, "WAO-win-x64.zip");
        var packageDirectory = Path.Combine(root, "package");
        var runnerDirectory = Path.Combine(root, "runner");
        try
        {
            Directory.CreateDirectory(root);
            await _app.Updates.DownloadAndVerifyAsync(manifest, zipPath);
            Directory.CreateDirectory(packageDirectory);
            ZipFile.ExtractToDirectory(zipPath, packageDirectory, true);
            File.Delete(zipPath);

            var packagedUpdater = Path.Combine(packageDirectory, "Wao.Updater.exe");
            if (!File.Exists(packagedUpdater)) throw new InvalidDataException("更新包缺少独立更新器");
            Directory.CreateDirectory(runnerDirectory);
            var updater = Path.Combine(runnerDirectory, "Wao.Updater.exe");
            File.Copy(packagedUpdater, updater, true);

            var startInfo = new ProcessStartInfo
            {
                FileName = updater,
                UseShellExecute = true,
                WorkingDirectory = runnerDirectory
            };
            startInfo.ArgumentList.Add("--package");
            startInfo.ArgumentList.Add(packageDirectory);
            startInfo.ArgumentList.Add("--target");
            startInfo.ArgumentList.Add(AppContext.BaseDirectory);
            startInfo.ArgumentList.Add("--pid");
            startInfo.ArgumentList.Add(Environment.ProcessId.ToString());
            startInfo.ArgumentList.Add("--launch");
            startInfo.ArgumentList.Add("WAO.exe");
            Process.Start(startInfo);
            Application.Current.Shutdown();
        }
        catch (Exception exception)
        {
            SetError("更新失败，当前版本未被修改", exception);
            try { if (Directory.Exists(root)) Directory.Delete(root, true); } catch { }
            UpdateButton.IsEnabled = true;
        }
    }

    private void SetBusy(string message) => StatusText.Text = message;
    private void SetReady(string message) => StatusText.Text = $"✓ {message}";
    private void SetError(string context, Exception exception) => StatusText.Text = $"⚠ {context}：{exception.Message}";

    private static System.Windows.Media.Brush FindBrush(string value) =>
        (System.Windows.Media.Brush)new System.Windows.Media.BrushConverter().ConvertFromString(value)!;

    private sealed record AiRunDisplay(
        string TaskTitle,
        string ProjectName,
        string Status,
        string DisplayProgress,
        string CreatedAt)
    {
        public static AiRunDisplay From(AiRun run) => new(
            run.TaskTitle,
            run.ProjectName,
            TranslateStatus(run.Status),
            !string.IsNullOrWhiteSpace(run.LatestProgress) ? run.LatestProgress : run.ResultSummary ?? "等待进度…",
            run.CreatedAt);

        private static string TranslateStatus(string value) => value switch
        {
            "queued" => "排队中",
            "claimed" => "已领取",
            "running" => "运行中",
            "succeeded" => "已成功",
            "failed" => "失败",
            "cancelled" => "已取消",
            _ => value
        };
    }
}
