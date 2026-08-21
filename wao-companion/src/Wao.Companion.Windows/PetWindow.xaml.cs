using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using MediaColor = System.Windows.Media.Color;
using MediaColorConverter = System.Windows.Media.ColorConverter;
using Forms = System.Windows.Forms;
using Wao.Companion.Core;

namespace Wao.Companion.Windows;

public partial class PetWindow : Window
{
    private bool _reducedMotion;
    private readonly DispatcherTimer _spriteTimer;
    private readonly DispatcherTimer _roamTimer;
    private int[] _frames = [0, 1];
    private int _frameIndex;
    private int _roamStepsRemaining;
    private bool _roamRight = true;
    private bool _dragging;
    private bool _landing;
    private bool _roamingPaused;
    private string _state = "IDLE";
    private DateTime _roamAfter = DateTime.UtcNow.AddSeconds(3);

    internal event EventHandler? ToggleTrayRequested;
    internal event EventHandler? OpenWaoRequested;
    internal event EventHandler? RefreshRequested;
    internal event EventHandler? HideRequested;
    internal event EventHandler<bool>? StartupToggled;
    internal event EventHandler? RePairRequested;
    internal event EventHandler? ExitRequested;
    internal event EventHandler? PositionCommitted;

    public PetWindow()
    {
        InitializeComponent();
        _reducedMotion = !SystemParameters.ClientAreaAnimation;
        _spriteTimer = new DispatcherTimer(TimeSpan.FromMilliseconds(180), DispatcherPriority.Render,
            (_, _) => AdvanceFrame(), Dispatcher);
        _roamTimer = new DispatcherTimer(TimeSpan.FromMilliseconds(90), DispatcherPriority.Background,
            (_, _) => AdvanceRoaming(), Dispatcher);
        Loaded += (_, _) => {
            _spriteTimer.Start();
            _roamTimer.Start();
        };
        Closed += (_, _) => {
            _spriteTimer.Stop();
            _roamTimer.Stop();
        };
        ApplyFrame(0);
    }

    internal void RestorePosition(double? requestedLeft, double? requestedTop)
    {
        Rect work = SystemParameters.WorkArea;
        CompanionPoint restored = CompanionState.RestorePosition(requestedLeft, requestedTop, Width, Height,
            new CompanionBounds(SystemParameters.VirtualScreenLeft, SystemParameters.VirtualScreenTop,
                SystemParameters.VirtualScreenWidth, SystemParameters.VirtualScreenHeight),
            new CompanionBounds(work.Left, work.Top, work.Width, work.Height));
        Left = restored.Left;
        Top = restored.Top;
    }

    internal void SetStartupChecked(bool value) => StartupItem.IsChecked = value;

    internal void PreparePreview() => ShowInTaskbar = true;

    internal void SetRoamingPaused(bool paused)
    {
        _roamingPaused = paused;
        if (paused) StopRoaming();
    }

    internal void SetState(string state, int unreadCount, string message)
    {
        _state = state;
        MessageText.Text = message;
        StateText.Text = state switch
        {
            "RUNNING" => "正在工作",
            "NEEDS_INPUT" => "需要你的确认",
            "READY" => "已经完成",
            "BLOCKED" => "遇到阻塞",
            _ => "空闲陪伴",
        };
        UnreadBadge.Visibility = unreadCount > 0 ? Visibility.Visible : Visibility.Collapsed;
        UnreadText.Text = unreadCount > 99 ? "99+" : unreadCount.ToString();
        Bubble.BorderBrush = new SolidColorBrush((MediaColor)MediaColorConverter.ConvertFromString(state switch
        {
            "NEEDS_INPUT" => "#F0C966",
            "BLOCKED" => "#E99A9A",
            "READY" => "#83CDA8",
            "RUNNING" => "#AFA1ED",
            _ => "#E7E1F5",
        }));
        StartStatusAnimation();
    }

    internal void SetOffline(string message)
    {
        _state = "OFFLINE";
        MessageText.Text = message;
        StateText.Text = "WAO 暂时离线 · 正在重试";
        Bubble.BorderBrush = new SolidColorBrush(MediaColor.FromRgb(190, 190, 198));
        StopAnimation();
        SetFrames(0);
    }

    internal static int[] FramesForState(string state) => state switch
    {
        "RUNNING" => [4, 5],
        "NEEDS_INPUT" => [11, 0],
        "READY" => [10, 1],
        "BLOCKED" => [8, 0],
        _ => [0, 1],
    };

    private void StartStatusAnimation()
    {
        StopAnimation();
        int[] statusFrames = FramesForState(_state);
        SetFrames(_reducedMotion ? [statusFrames[0]] : statusFrames);
        if (_reducedMotion) return;
        TimeSpan duration = _state switch
        {
            "RUNNING" => TimeSpan.FromMilliseconds(440),
            "READY" => TimeSpan.FromMilliseconds(620),
            "NEEDS_INPUT" => TimeSpan.FromMilliseconds(820),
            "BLOCKED" => TimeSpan.FromMilliseconds(1500),
            _ => TimeSpan.FromMilliseconds(1250),
        };
        double lift = _state switch { "RUNNING" => -6, "READY" => -10, "BLOCKED" => -2, _ => -4 };
        PetMove.BeginAnimation(TranslateTransform.YProperty, Loop(0, lift, duration));
        if (_state == "NEEDS_INPUT") PetRotate.BeginAnimation(RotateTransform.AngleProperty, Loop(-3, 3, duration));
        if (_state == "READY") {
            PetScale.BeginAnimation(ScaleTransform.ScaleXProperty, Loop(0.96, 1.05, duration));
            PetScale.BeginAnimation(ScaleTransform.ScaleYProperty, Loop(0.96, 1.05, duration));
        }
    }

    private void SetFrames(params int[] frames)
    {
        if (frames.Length == 0) return;
        if (_frames.SequenceEqual(frames)) return;
        _frames = frames;
        _frameIndex = 0;
        ApplyFrame(_frames[0]);
    }

    private void AdvanceFrame()
    {
        if (_reducedMotion || _frames.Length < 2) return;
        _frameIndex = (_frameIndex + 1) % _frames.Length;
        ApplyFrame(_frames[_frameIndex]);
    }

    private void ApplyFrame(int frame)
    {
        int column = frame % 4;
        int row = frame / 4;
        PetBrush.Viewbox = new Rect(column * 0.25, row / 3d, 0.25, 1d / 3d);
    }

    private void AdvanceRoaming()
    {
        if (_reducedMotion || _roamingPaused || _dragging || _landing || !IsVisible ||
            (_state != "IDLE" && _state != "READY")) return;
        if (_roamStepsRemaining <= 0) {
            if (DateTime.UtcNow < _roamAfter) return;
            Rect area = CurrentWorkArea();
            if (Left <= area.Left + 10) _roamRight = true;
            else if (Left >= area.Right - Width - 10) _roamRight = false;
            else _roamRight = Random.Shared.Next(2) == 0;
            _roamStepsRemaining = Random.Shared.Next(20, 42);
            SetFrames(_roamRight ? [4, 5] : [2, 3]);
        }

        Rect work = CurrentWorkArea();
        double next = Left + (_roamRight ? 2.4 : -2.4);
        Left = Math.Clamp(next, work.Left, work.Right - Width);
        _roamStepsRemaining--;
        if (_roamStepsRemaining == 0) {
            _roamAfter = DateTime.UtcNow.AddSeconds(Random.Shared.Next(3, 7));
            StartStatusAnimation();
        }
    }

    private Rect CurrentWorkArea()
    {
        IntPtr handle = new WindowInteropHelper(this).Handle;
        if (handle == IntPtr.Zero || PresentationSource.FromVisual(this)?.CompositionTarget is not { } target) {
            return SystemParameters.WorkArea;
        }
        System.Drawing.Rectangle pixels = Forms.Screen.FromHandle(handle).WorkingArea;
        System.Windows.Point topLeft = target.TransformFromDevice.Transform(
            new System.Windows.Point(pixels.Left, pixels.Top));
        System.Windows.Point bottomRight = target.TransformFromDevice.Transform(
            new System.Windows.Point(pixels.Right, pixels.Bottom));
        return new Rect(topLeft, bottomRight);
    }

    private void StopRoaming()
    {
        _roamStepsRemaining = 0;
        _roamAfter = DateTime.UtcNow.AddSeconds(3);
        if (!_dragging && !_landing) StartStatusAnimation();
    }

    private static DoubleAnimation Loop(double from, double to, TimeSpan duration) => new(from, to, duration)
    {
        AutoReverse = true,
        RepeatBehavior = RepeatBehavior.Forever,
        EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut },
    };

    private void StopAnimation()
    {
        PetMove.BeginAnimation(TranslateTransform.YProperty, null);
        PetRotate.BeginAnimation(RotateTransform.AngleProperty, null);
        PetScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
        PetScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
        PetMove.Y = 0;
        PetRotate.Angle = 0;
        PetScale.ScaleX = PetScale.ScaleY = 1;
    }

    private void DragSurface_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left) return;
        double beforeLeft = Left;
        double beforeTop = Top;
        _dragging = true;
        StopAnimation();
        SetFrames(_reducedMotion ? [6] : [6, 7]);
        try { DragMove(); }
        catch (InvalidOperationException) {
            StartStatusAnimation();
            return;
        }
        finally { _dragging = false; }
        bool moved = Math.Abs(Left - beforeLeft) > 3 || Math.Abs(Top - beforeTop) > 3;
        if (moved) {
            PositionCommitted?.Invoke(this, EventArgs.Empty);
            _ = PlayLandingAsync();
        } else {
            StartStatusAnimation();
            ToggleTrayRequested?.Invoke(this, EventArgs.Empty);
        }
    }

    private async Task PlayLandingAsync()
    {
        _landing = true;
        SetFrames(8);
        await Task.Delay(130);
        if (!IsLoaded) {
            _landing = false;
            return;
        }
        SetFrames(9);
        await Task.Delay(160);
        _landing = false;
        if (IsLoaded) StartStatusAnimation();
    }

    private void OpenWao_Click(object sender, RoutedEventArgs e) => OpenWaoRequested?.Invoke(this, EventArgs.Empty);
    private void Refresh_Click(object sender, RoutedEventArgs e) => RefreshRequested?.Invoke(this, EventArgs.Empty);
    private void Hide_Click(object sender, RoutedEventArgs e) => HideRequested?.Invoke(this, EventArgs.Empty);
    private void RePair_Click(object sender, RoutedEventArgs e) => RePairRequested?.Invoke(this, EventArgs.Empty);
    private void Exit_Click(object sender, RoutedEventArgs e) => ExitRequested?.Invoke(this, EventArgs.Empty);
    private void Startup_Click(object sender, RoutedEventArgs e) => StartupToggled?.Invoke(this, StartupItem.IsChecked);
}
