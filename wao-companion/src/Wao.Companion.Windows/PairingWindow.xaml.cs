using System.Diagnostics;
using System.ComponentModel;
using System.Windows;
using Wao.Companion.Core;

namespace Wao.Companion.Windows;

public partial class PairingWindow : Window
{
    private string? _verificationUrl;
    internal bool AllowClose { get; set; }
    internal event EventHandler? RetryRequested;

    public PairingWindow()
    {
        InitializeComponent();
        Closing += OnClosing;
    }

    internal void ShowCreating()
    {
        StatusText.Text = "正在创建安全配对码…";
        CodeText.Text = "••••-••••";
        HintText.Text = "桌宠不会要求你输入 WAO 密码。";
        OpenButton.IsEnabled = false;
        RetryButton.Visibility = Visibility.Collapsed;
    }

    internal void ShowCode(PairingCreated pairing)
    {
        _verificationUrl = pairing.VerificationUrl;
        StatusText.Text = "请在打开的 WAO 网页确认这台电脑";
        CodeText.Text = pairing.UserCode;
        HintText.Text = "确认后保持此窗口打开，桌宠会自动完成连接。";
        OpenButton.IsEnabled = true;
        RetryButton.Visibility = Visibility.Collapsed;
    }

    internal void ShowApproved()
    {
        StatusText.Text = "网页已批准，正在激活桌宠…";
        HintText.Text = "马上就好。";
        OpenButton.IsEnabled = false;
    }

    internal void ShowError(string message)
    {
        StatusText.Text = "暂时无法完成配对";
        HintText.Text = message;
        OpenButton.IsEnabled = false;
        RetryButton.Visibility = Visibility.Visible;
    }

    private void OpenButton_Click(object sender, RoutedEventArgs e)
    {
        if (_verificationUrl is null) return;
        Process.Start(new ProcessStartInfo(_verificationUrl) { UseShellExecute = true });
    }

    private void RetryButton_Click(object sender, RoutedEventArgs e) => RetryRequested?.Invoke(this, EventArgs.Empty);

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        if (AllowClose) return;
        e.Cancel = true;
        Hide();
    }
}
