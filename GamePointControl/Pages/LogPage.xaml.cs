using GamePointControl.Services;

namespace GamePointControl.Pages;

/// <summary>Экран журнала (ТЗ 5.5): записи с временем, очистка, копирование.</summary>
public partial class LogPage : ContentPage
{
    private bool subscribed;

    public LogPage()
    {
        InitializeComponent();
    }

    protected override void OnAppearing()
    {
        base.OnAppearing();
        if (!subscribed)
        {
            subscribed = true;
            CommandLog.Current.Changed += OnLogChanged;
        }
        Refresh();
    }

    protected override void OnDisappearing()
    {
        base.OnDisappearing();
        CommandLog.Current.Changed -= OnLogChanged;
        subscribed = false;
    }

    private void OnLogChanged(object? sender, EventArgs e) =>
        MainThread.BeginInvokeOnMainThread(Refresh);

    private void Refresh()
    {
        var entries = CommandLog.Current.Snapshot();
        var sb = new System.Text.StringBuilder();
        foreach (var e in entries) sb.AppendLine(e.Formatted);
        LogText.Text = sb.ToString();
    }

    private async void OnClear(object? sender, EventArgs e)
    {
        var yes = await DisplayAlert("Очистка журнала", "Удалить все записи?", "Да", "Отмена");
        if (yes) CommandLog.Current.Clear();
    }

    private async void OnCopy(object? sender, EventArgs e)
    {
        await Clipboard.Default.SetTextAsync(CommandLog.Current.AsText());
        await DisplayAlert("Журнал", "Скопировано в буфер обмена.", "OK");
    }
}
