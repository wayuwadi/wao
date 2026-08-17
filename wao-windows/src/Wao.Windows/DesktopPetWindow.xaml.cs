using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Animation;

namespace Wao.Windows;

public partial class DesktopPetWindow : Window
{
    private readonly MainWindow _ownerWindow;

    public DesktopPetWindow(MainWindow ownerWindow)
    {
        _ownerWindow = ownerWindow;
        InitializeComponent();
        Left = SystemParameters.WorkArea.Right - Width - 22;
        Top = SystemParameters.WorkArea.Bottom - Height - 20;
        Loaded += (_, _) => StartAnimation();
    }

    public void SetMessage(string message)
    {
        MessageText.Text = message;
    }

    private void StartAnimation()
    {
        var animation = new DoubleAnimation(0, -7, TimeSpan.FromSeconds(1.15))
        {
            AutoReverse = true,
            RepeatBehavior = RepeatBehavior.Forever,
            EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut }
        };
        PetBounce.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, animation);
    }

    private void Window_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private void Open_Click(object sender, RoutedEventArgs e) => _ownerWindow.BringToFront();

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await _ownerWindow.RefreshCurrentAsync();

    private void Hide_Click(object sender, RoutedEventArgs e) => _ownerWindow.SetPetVisibility(false);
}
