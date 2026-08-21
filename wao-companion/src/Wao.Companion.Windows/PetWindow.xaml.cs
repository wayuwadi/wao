using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using MediaColor = System.Windows.Media.Color;
using MediaColorConverter = System.Windows.Media.ColorConverter;
using Wao.Companion.Core;

namespace Wao.Companion.Windows;

public partial class PetWindow : Window
{
    private bool _reducedMotion;

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

    internal void SetState(string state, int unreadCount, string message)
    {
        int frame = state switch
        {
            "RUNNING" => 1,
            "NEEDS_INPUT" => 2,
            "READY" => 3,
            "BLOCKED" => 4,
            _ => 0,
        };
        PetBrush.Viewbox = new Rect(frame * 0.2, 0, 0.2, 1);
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
        StartAnimation(state);
    }

    internal void SetOffline(string message)
    {
        PetBrush.Viewbox = new Rect(0, 0, 0.2, 1);
        MessageText.Text = message;
        StateText.Text = "WAO 暂时离线 · 正在重试";
        Bubble.BorderBrush = new SolidColorBrush(MediaColor.FromRgb(190, 190, 198));
        StopAnimation();
    }

    private void StartAnimation(string state)
    {
        StopAnimation();
        if (_reducedMotion) return;
        TimeSpan duration = state switch
        {
            "RUNNING" => TimeSpan.FromMilliseconds(440),
            "READY" => TimeSpan.FromMilliseconds(620),
            "NEEDS_INPUT" => TimeSpan.FromMilliseconds(820),
            "BLOCKED" => TimeSpan.FromMilliseconds(1500),
            _ => TimeSpan.FromMilliseconds(1250),
        };
        double lift = state switch { "RUNNING" => -9, "READY" => -13, "BLOCKED" => -2, _ => -5 };
        PetMove.BeginAnimation(TranslateTransform.YProperty, Loop(0, lift, duration));
        if (state == "NEEDS_INPUT") PetRotate.BeginAnimation(RotateTransform.AngleProperty, Loop(-3, 3, duration));
        if (state == "READY") {
            PetScale.BeginAnimation(ScaleTransform.ScaleXProperty, Loop(0.96, 1.05, duration));
            PetScale.BeginAnimation(ScaleTransform.ScaleYProperty, Loop(0.96, 1.05, duration));
        }
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
        try { DragMove(); } catch (InvalidOperationException) { return; }
        bool moved = Math.Abs(Left - beforeLeft) > 3 || Math.Abs(Top - beforeTop) > 3;
        if (moved) PositionCommitted?.Invoke(this, EventArgs.Empty);
        else ToggleTrayRequested?.Invoke(this, EventArgs.Empty);
    }

    private void OpenWao_Click(object sender, RoutedEventArgs e) => OpenWaoRequested?.Invoke(this, EventArgs.Empty);
    private void Refresh_Click(object sender, RoutedEventArgs e) => RefreshRequested?.Invoke(this, EventArgs.Empty);
    private void Hide_Click(object sender, RoutedEventArgs e) => HideRequested?.Invoke(this, EventArgs.Empty);
    private void RePair_Click(object sender, RoutedEventArgs e) => RePairRequested?.Invoke(this, EventArgs.Empty);
    private void Exit_Click(object sender, RoutedEventArgs e) => ExitRequested?.Invoke(this, EventArgs.Empty);
    private void Startup_Click(object sender, RoutedEventArgs e) => StartupToggled?.Invoke(this, StartupItem.IsChecked);
}
