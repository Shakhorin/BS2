using GamePointControl.Models;
using GamePointControl.Services;
using DeviceType = GamePointControl.Models.DeviceType;

namespace GamePointControl.Pages;

/// <summary>Экран пресетов (ТЗ 5.4): список, создание, редактирование, удаление, применение.</summary>
public partial class PresetsPage : ContentPage
{
    private readonly PresetStore store = PresetStore.Current;

    public PresetsPage()
    {
        InitializeComponent();
    }

    protected override void OnAppearing()
    {
        base.OnAppearing();
        Reload();
    }

    private void Reload() => PresetList.ItemsSource = store.All();

    private async void OnCreate(object? sender, EventArgs e) => await EditAsync(null);

    private async void OnEditPreset(object? sender, EventArgs e)
    {
        if (sender is BindableObject bo && bo.BindingContext is Preset p) await EditAsync(p);
    }

    private async void OnDeletePreset(object? sender, EventArgs e)
    {
        if (sender is not BindableObject bo || bo.BindingContext is not Preset p) return;
        var confirm = await DisplayAlert("Удаление пресета", $"Удалить «{p.Name}»?", "Да", "Отмена");
        if (!confirm) return;
        store.Delete(p.Id);
        CommandLog.Current.Info($"Пресет удалён: {p.Name}");
        Reload();
    }

    private async void OnApplyPreset(object? sender, EventArgs e)
    {
        if (sender is not BindableObject bo || bo.BindingContext is not Preset p) return;
#if ANDROID
        var bt = BtManager.Current;
        if (bt.State != ConnState.Connected)
        {
            await DisplayAlert("Ошибка", "Нет подключённого устройства. Сначала выполните подключение.", "OK");
            return;
        }
        CommandLog.Current.Info($"Применение пресета «{p.Name}»");
        ((Button)sender!).IsEnabled = false;
        await Task.Run(() =>
        {
            var results = new List<(string Cmd, string? Resp)>();
            switch (p.Type)
            {
                case DeviceType.CapturePoint:
                    results.Add(("T" + BtManager.FormatFloat(p.WinTimeMin), bt.SendWinTime(p.WinTimeMin)));
                    results.Add(("D" + BtManager.FormatFloat(p.SwitchDelaySec), bt.SendSwitchDelay(p.SwitchDelaySec)));
                    break;
                case DeviceType.Medkit:
                    results.Add(("I" + p.MedkitIntervalMin, bt.SendMedkitInterval(p.MedkitIntervalMin)));
                    break;
            }
            var failed = results.FindAll(r => r.Resp == null);
            MainThread.BeginInvokeOnMainThread(async () =>
            {
                if (sender is Button b) b.IsEnabled = true;
                if (failed.Count == 0)
                    await DisplayAlert("Готово", $"Пресет «{p.Name}» применён.", "OK");
                else
                    await DisplayAlert("Частичный успех",
                        $"Часть команд пресета «{p.Name}» не подтверждена устройством.", "OK");
            });
        });
#else
        await DisplayAlert("Информация", "Только Android", "OK");
#endif
    }

    // ------------------------------------------------------------------
    // Диалог создания/редактирования
    // ------------------------------------------------------------------

    private async Task EditAsync(Preset? existing)
    {
        var etName = new Entry { Placeholder = "Название (например, Сценарий А)", Text = existing?.Name };
        var rbCapture = new RadioButton { Content = "Точка захвата", IsChecked = existing == null || existing.Type == DeviceType.CapturePoint };
        var rbMedkit = new RadioButton { Content = "Аптечка", IsChecked = existing?.Type == DeviceType.Medkit };
        var group = new RadioGroup { rbCapture, rbMedkit };

        var etWin = new Entry { Keyboard = Keyboard.Numeric, Text = existing?.WinTimeMin.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "1.5" };
        var etDelay = new Entry { Keyboard = Keyboard.Numeric, Text = existing?.SwitchDelaySec.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "45" };
        var etInterval = new Entry { Keyboard = Keyboard.Numeric, Text = existing?.MedkitIntervalMin.ToString() ?? "10" };

        var layout = new VerticalStackLayout
        {
            Padding = 20,
            Spacing = 8,
            Children =
            {
                new Label { Text = "Название" },
                etName,
                new Label { Text = "Тип устройства" },
                group,
                new Label { Text = "WIN_TIME, мин (точка захвата)" },
                etWin,
                new Label { Text = "switchDelay, сек (точка захвата)" },
                etDelay,
                new Label { Text = "Интервал воскрешения, мин (аптечка)" },
                etInterval,
            }
        };

        var page = new ContentPage { Content = new ScrollView { Content = layout } };
        page.Title = existing == null ? "Новый пресет" : "Изменение пресета";

        var okButton = new Button { Text = "Сохранить" };
        var cancelButton = new Button { Text = "Отмена" };
        bool saved = false;

        var toolbar = new HorizontalStackLayout
        {
            Spacing = 8,
            Padding = 16,
            Children = { okButton, cancelButton }
        };

        page.Content = new Grid
        {
            RowDefinitions = Rows.Define(Star, Auto),
            Children = { layout.AsRow(0), toolbar.AsRow(1) }
        };

        okButton.Clicked += async (_, _) =>
        {
            var name = etName.Text?.Trim() ?? "";
            if (name.Length == 0)
            {
                await page.DisplayAlert("Ошибка", "Введите название пресета.", "OK");
                return;
            }
            var win = ParseDouble(etWin.Text) ?? 1.5;
            var delay = ParseDouble(etDelay.Text) ?? 45;
            var interval = int.TryParse(etInterval.Text?.Trim(), out var iv) ? iv : 10;

            if (!BtManager.ValidateWinTime(win))
            {
                await page.DisplayAlert("Ошибка", "WIN_TIME должен быть больше 0.05 мин.", "OK");
                return;
            }
            if (!BtManager.ValidateSwitchDelay(delay))
            {
                await page.DisplayAlert("Ошибка", "Задержка должна быть >= 0 сек.", "OK");
                return;
            }
            if (!BtManager.ValidateMedkitInterval(interval))
            {
                await page.DisplayAlert("Ошибка", "Интервал должен быть больше 0.", "OK");
                return;
            }

            var preset = new Preset
            {
                Id = existing?.Id ?? Guid.NewGuid().ToString("N"),
                Name = name,
                Type = rbMedkit.IsChecked ? DeviceType.Medkit : DeviceType.CapturePoint,
                WinTimeMin = win,
                SwitchDelaySec = delay,
                MedkitIntervalMin = interval
            };
            store.Save(preset);
            CommandLog.Current.Info($"Пресет сохранён: {name}");
            saved = true;
            await Navigation.PopModalAsync();
        };
        cancelButton.Clicked += async (_, _) => await Navigation.PopModalAsync();

        await Navigation.PushModalAsync(page);
        _ = saved;
    }

    private static double? ParseDouble(string? s) =>
        double.TryParse(s?.Trim().Replace(',', '.'), System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture, out var v) ? v : null;
}

/// <summary>Отображаемые свойства пресета для списка (без XAML-конвертеров).</summary>
public static class PresetDisplay
{
    public static string Title(this Preset p) => $"{p.Name} ({p.Type.ToRussian()})";
}
