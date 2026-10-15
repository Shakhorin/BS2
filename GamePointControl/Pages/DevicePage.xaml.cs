using GamePointControl.Models;
using GamePointControl.Services;
using DeviceType = GamePointControl.Models.DeviceType;

namespace GamePointControl.Pages;

/// <summary>
/// Экран устройства (ТЗ 5.3): параметры в зависимости от типа, валидация
/// с подсветкой ошибок, кнопки «Применить»/«Сброс», область ответов.
/// </summary>
public partial class DevicePage : ContentPage
{
    private DeviceType deviceType = DeviceType.CapturePoint;
    private string? deviceAddress;
    private bool subscribed;

#if ANDROID
    private static BtManager Bt => BtManager.Current;
#endif

    public DevicePage()
    {
        InitializeComponent();
    }

    public DevicePage(string? address) : this()
    {
        deviceAddress = address;
#if ANDROID
        // Автоопределение типа по имени + сохранённый ранее тип (ТЗ 4.4.3).
        var name = Bt.ConnectedDevice?.Address == address ? Bt.ConnectedDevice.Name : null;
        var saved = Preferences.Default.Get("type_" + (address ?? "default"), (string?)null);
        deviceType = saved != null && Enum.TryParse<DeviceType>(saved, out var t)
            ? t
            : DeviceTypeExtensions.FromName(name);
#endif
    }

    protected override void OnAppearing()
    {
        base.OnAppearing();
        if (!subscribed)
        {
            subscribed = true;
#if ANDROID
            Bt.ConnectionStateChanged += OnConnChanged;
            Bt.DataReceived += OnData;
            Bt.ErrorOccurred += OnError;
#endif
        }
        ApplyTypeToUi();
        UpdateConnectionUi();
        UpdateValidation();
    }

    protected override void OnDisappearing()
    {
        base.OnDisappearing();
#if ANDROID
        Bt.ConnectionStateChanged -= OnConnChanged;
        Bt.DataReceived -= OnData;
        Bt.ErrorOccurred -= OnError;
#endif
        subscribed = false;
    }

    // ------------------------------------------------------------------
    // Тип устройства
    // ------------------------------------------------------------------

    private void OnTypeChanged(object? sender, CheckedChangedEventArgs e)
    {
        if (!e.Value) return;
        deviceType = RbMedkit.IsChecked ? DeviceType.Medkit : DeviceType.CapturePoint;
        Preferences.Default.Set("type_" + (deviceAddress ?? "default"), deviceType.ToString());
        ApplyTypeToUi();
        UpdateValidation();
    }

    private void ApplyTypeToUi()
    {
        Title = "Устройство — " + deviceType.ToRussian();
        CaptureGroup.IsVisible = deviceType == DeviceType.CapturePoint;
        MedkitGroup.IsVisible = deviceType == DeviceType.Medkit;
        if (deviceType == DeviceType.Medkit && !RbMedkit.IsChecked) RbMedkit.IsChecked = true;
        if (deviceType == DeviceType.CapturePoint && !RbCapture.IsChecked) RbCapture.IsChecked = true;
    }

    // ------------------------------------------------------------------
    // Валидация (ТЗ 4.2, 4.3, 7)
    // ------------------------------------------------------------------

    private double? ParseWinTime() =>
        double.TryParse(EtWinTime.Text?.Trim().Replace(',', '.'),
            System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture, out var v) ? v : null;

    private double? ParseDelay() =>
        double.TryParse(EtDelay.Text?.Trim().Replace(',', '.'),
            System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture, out var v) ? v : null;

    private int? ParseInterval() =>
        int.TryParse(EtInterval.Text?.Trim(), out var v) ? v : null;

    private void UpdateValidation()
    {
        var ok = true;
        if (deviceType == DeviceType.CapturePoint)
        {
            var t = ParseWinTime();
            var tOk = t != null && BtManager.ValidateWinTime(t.Value);
            ShowError(ErrWinTime, !tOk, "WIN_TIME должен быть больше 0.05 мин");
            ok &= tOk;

            var d = ParseDelay();
            var dOk = d != null && BtManager.ValidateSwitchDelay(d.Value);
            ShowError(ErrDelay, !dOk, "Задержка должна быть >= 0 сек");
            ok &= dOk;
        }
        else
        {
            var i = ParseInterval();
            var iOk = i != null && BtManager.ValidateMedkitInterval(i.Value);
            ShowError(ErrInterval, !iOk, "Интервал должен быть целым и больше 0");
            ok &= iOk;
        }
#if ANDROID
        var connected = Bt.State == ConnState.Connected;
#else
        var connected = false;
#endif
        // Блокировка отправки при некорректном вводе или отсутствии соединения (ТЗ 7).
        BtnApply.IsEnabled = ok && connected;
        BtnReset.IsEnabled = connected;
    }

    private static void ShowError(Label label, bool hasError, string text)
    {
        label.Text = hasError ? text : "";
        label.IsVisible = hasError;
    }

    private void OnInputChanged(object? sender, TextChangedEventArgs e) => UpdateValidation();

    // ------------------------------------------------------------------
    // Отправка команд (раздел 6 ТЗ) — в фоновом потоке, ответ ~3 с
    // ------------------------------------------------------------------

    private async void OnApply(object? sender, EventArgs e)
    {
#if ANDROID
        if (Bt.State != ConnState.Connected)
        {
            await DisplayAlert("Ошибка", "Устройство не подключено", "OK");
            return;
        }
        UpdateValidation();
        if (!BtnApply.IsEnabled) return;

        BtnApply.IsEnabled = false;
        AppendResponse("Отправка…");
        var type = deviceType;
        var win = ParseWinTime();
        var delay = ParseDelay();
        var interval = ParseInterval();

        await Task.Run(() =>
        {
            var results = new List<(string Cmd, string? Resp)>();
            if (type == DeviceType.CapturePoint)
            {
                if (win != null)
                    results.Add(("T" + BtManager.FormatFloat(win.Value), Bt.SendWinTime(win.Value)));
                if (delay != null)
                    results.Add(("D" + BtManager.FormatFloat(delay.Value), Bt.SendSwitchDelay(delay.Value)));
            }
            else
            {
                if (interval != null)
                    results.Add(("I" + interval, Bt.SendMedkitInterval(interval.Value)));
            }
            MainThread.BeginInvokeOnMainThread(() =>
            {
                foreach (var (cmd, resp) in results)
                {
                    if (resp != null) AppendResponse($"{cmd} → {resp}");
                    else AppendResponse($"ОШИБКА: нет ответа на {cmd}");
                }
                BtnApply.IsEnabled = true;
                UpdateValidation();
            });
        });
#else
        await DisplayAlert("Информация", "Только Android", "OK");
#endif
    }

    private async void OnReset(object? sender, EventArgs e)
    {
#if ANDROID
        if (Bt.State != ConnState.Connected)
        {
            await DisplayAlert("Ошибка", "Устройство не подключено", "OK");
            return;
        }
        BtnReset.IsEnabled = false;
        await Task.Run(() =>
        {
            var resp = Bt.SendReset();
            MainThread.BeginInvokeOnMainThread(() =>
            {
                AppendResponse(resp != null ? $"R → {resp}" : "ОШИБКА: нет ответа на R");
                BtnReset.IsEnabled = true;
            });
        });
#else
        await Task.CompletedTask;
#endif
    }

    // ------------------------------------------------------------------
    // События Bluetooth
    // ------------------------------------------------------------------

#if ANDROID
    private void OnConnChanged(ConnState state, BtDeviceInfo? dev) =>
        MainThread.BeginInvokeOnMainThread(UpdateConnectionUi);

    private void OnData(string line) =>
        MainThread.BeginInvokeOnMainThread(() => AppendResponse("≪ " + line));

    private void OnError(string msg) => MainThread.BeginInvokeOnMainThread(async () =>
    {
        AppendResponse("ОШИБКА: " + msg);
        // Потеря соединения — возврат на главный экран (ТЗ 7).
        if (Bt.State == ConnState.Disconnected)
        {
            await DisplayAlert("Соединение потеряно", msg, "OK");
            if (Navigation.NavigationStack.Count > 1)
                await Navigation.PopToRootAsync();
        }
    });
#endif

    private void UpdateConnectionUi()
    {
#if ANDROID
        ConnStatus.Text = Bt.State switch
        {
            ConnState.Connected => "Подключено: " + (Bt.ConnectedDevice?.DisplayName ?? ""),
            ConnState.Connecting => "Подключение…",
            _ => "Не подключено",
        };
        ConnStatus.TextColor = Microsoft.Maui.Graphics.Color.Parse(
            Bt.State == ConnState.Connected ? "#2E7D32" : "#C62828");
#endif
        UpdateValidation();
    }

    private void AppendResponse(string text)
    {
        TvResponses.Text += "\n" + text;
        ResponsesScroll.ScrollToAsync(0, TvResponses.Height, animated: false);
    }
}
