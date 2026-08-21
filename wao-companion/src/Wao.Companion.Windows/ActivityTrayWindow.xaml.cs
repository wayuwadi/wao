using System.Windows;
using Wao.Companion.Core;

namespace Wao.Companion.Windows;

public partial class ActivityTrayWindow : Window
{
    internal event EventHandler<CompanionActivity>? ActivitySelected;
    internal event EventHandler? ReadAllRequested;

    public ActivityTrayWindow()
    {
        InitializeComponent();
        Deactivated += (_, _) => Hide();
    }

    internal void SetSnapshot(CompanionSnapshot snapshot)
    {
        ActivityItems.ItemsSource = snapshot.Activities.Select(ActivityRow.From).ToList();
        EmptyPanel.Visibility = snapshot.Activities.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        ReadAllButton.IsEnabled = snapshot.UnreadCount > 0;
        SummaryText.Text = snapshot.UnreadCount > 0
            ? $"{snapshot.UnreadCount} 条活动需要查看"
            : snapshot.Activities.Any(item => item.Status == "RUNNING") ? "AI 正在工作" : "没有需要处理的活动";
    }

    internal void ShowBeside(PetWindow pet)
    {
        Rect area = SystemParameters.WorkArea;
        double leftSide = pet.Left - Width - 10;
        Left = leftSide >= area.Left ? leftSide : Math.Min(area.Right - Width, pet.Left + pet.Width + 10);
        Top = Math.Clamp(pet.Top + pet.Height - Height, area.Top, area.Bottom - Height);
        Show();
        Activate();
    }

    private void Activity_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: CompanionActivity activity }) {
            ActivitySelected?.Invoke(this, activity);
        }
    }

    private void ReadAll_Click(object sender, RoutedEventArgs e) => ReadAllRequested?.Invoke(this, EventArgs.Empty);

    private sealed record ActivityRow(CompanionActivity Activity, string StatusLabel, string Accent,
        string BadgeBackground)
    {
        internal static ActivityRow From(CompanionActivity activity) => activity.Status switch
        {
            "NEEDS_INPUT" => new(activity, "需确认", "#A87516", "#FFF1C9"),
            "BLOCKED" => new(activity, "阻塞", "#B94C55", "#FDE4E6"),
            "READY" => new(activity, activity.Unread ? "已完成" : "已读", "#2E815B", "#DCF4E7"),
            "RUNNING" => new(activity, "运行中", "#6650C9", "#ECE8FF"),
            _ => new(activity, activity.Status, "#6E687A", "#EEECF2"),
        };
    }
}
