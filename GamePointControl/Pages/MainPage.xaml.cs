using System.Collections.ObjectModel;
using System.ComponentModel;
#if ANDROID
using Android.Bluetooth;
#endif

namespace GamePointControl.Pages;

public enum BondModel { None, Bonding, Bonded }

/// <summary>Элемент списка устройств с индикацией состояния (ТЗ 4.1.7, 5.2).</summary>
public sealed class DeviceListItem : INotifyPropertyChanged
{
    public string Address { get; init; } = "";
    private string name = "";
    public string Name
    {
        get => name;
        set { name = value; Raise(nameof(Name)); Raise(nameof(DisplayName)); }
    }
    private int rssi;
    public int Rssi
    {
        get => rssi;
        set { rssi = value; Raise(nameof(Rssi)); Raise(nameof(RssiText)); }
    }
    private BondModel bond;
    public BondModel Bond
    {
        get => bond;
        set { bond = value; Raise(nameof(Bond)); Raise(nameof(StatusText)); Raise(nameof(StatusColor)); }
    }
    private bool connected;
    public bool Connected
    {
        get => connected;
        set { connected = value; Raise(nameof(Connected)); Raise(nameof(StatusText)); Raise(nameof(StatusColor)); }
    }

    public string DisplayName => string.IsNullOrWhiteSpace(Name) ? Address : $"{Name}  ({Address})";
    public string RssiText => Rssi != 0 ? $"{Rssi} dBm" : "";
    public string StatusText => Connected ? "Подключено" : Bond switch
    {
        BondModel.Bonded => "Сопряжено",
        BondModel.Bonding => "Сопряжение…",
        _ => "Не сопряжено",
    };
    public string StatusColor => Connected ? "#2E7D32" : Bond == BondModel.Bonded ? "#1565C0" : "#9E9E9E";

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Raise(string p) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(p));
}

public partial class MainPage : ContentPage
{
    private readonly ObservableCollection<DeviceListItem> devices = new();
    private DeviceListItem? selected;
    private bool askedLast;

#if ANDROID
    private static Services.BtManager Bt => Services.BtManager.Current;
#endif

    public MainPage()
    {
        InitializeComponent();
        DeviceList.ItemsSource = devices;
        AutoReconnectSwitch.IsToggled = Preferences.Default.Get("auto_reconnect", true);
#if ANDROID
        if (!initialized)
        {
            initialized = true;
            Bt.AutoReconnect = AutoReconnectSwitch.IsToggled;
            Bt.DeviceFound += info => MainThread.BeginInvokeOnMainThread(() => RefreshItem(info));
            Bt.ScanFinished += () => MainThread.BeginInvokeOnMainThread(() =>
            {
                BtnScan.IsEnabled = true;
                BtnScan.Text = "Сканировать";
            });
            Bt.BondStateChanged += info => MainThread.BeginInvokeOnMainThread(() => RefreshItem(info));
            Bt.ConnectionStateChanged += (state, dev) => MainThread.BeginInvokeOnMainThread(() =>
            {
                UpdateConnStatus();
                if (dev != null) RefreshItem(dev);
                UpdateButtons();
            });
            Bt.AdapterStateChanged += _ => MainThread.BeginInvokeOnMainThread(UpdateConnStatus);
            Bt.ErrorOccurred += msg => MainThread.BeginInvokeOnMainThread(async () =>
                await DisplayAlert("Ошибка", msg, "OK"));
        }
#endif
        UpdateConnStatus();
    }

    private static bool initialized;

#if ANDROID
    private void RefreshItem(Services.BtDeviceInfo info)
    {
        var item = devices.FirstOrDefault(d => d.Address == info.Address);
        if (item == null)
        {
            item = new DeviceListItem { Address = info.Address };
            devices.Add(item);
        }
        item.Name = info.Name;
        item.Rssi = info.Rssi;
        item.Bond = info.BondState switch
        {
            Bond.Bonded => BondModel.Bonded,
            Bond.Bonding => BondModel.Bonding,
            _ => BondModel.None,
        };
        item.Connected = Bt.State == Services.ConnState.Connected && Bt.ConnectedDevice?.Address == info.Address;
        ScanHint.Text = $"Найдено устройств: {devices.Count}";
    }
#endif

    private void UpdateConnStatus()
    {
#if ANDROID
        ConnStatus.Text = Bt.State switch
        {
            Services.ConnState.Connected => "Подключено: " + (Bt.ConnectedDevice?.DisplayName ?? ""),
            Services.ConnState.Connecting => "Подключение…",
            _ => "Не подключено",
        };
        ConnStatus.TextColor = Microsoft.Maui.Graphics.Color.Parse(
            Bt.State == Services.ConnState.Connected ? "#2E7D32" : "#9E9E9E");
        BtnDisconnect.IsEnabled = Bt.State is Services.ConnState.Connected or Services.ConnState.Connecting;
        if (!Bt.HasAdapter())
            ScanHint.Text = "Bluetooth-адаптер отсутствует на этом устройстве";
        else if (!Bt.IsAdapterEnabled())
            ScanHint.Text = "Bluetooth выключен — нажмите «Включить Bluetooth»";
#else
        ConnStatus.Text = "Bluetooth доступен только на Android";
#endif
        UpdateButtons();
    }

    private void OnDeviceSelected(object? sender, SelectionChangedEventArgs e)
    {
        selected = e.CurrentSelection.FirstOrDefault() as DeviceListItem;
        SelectedInfo.Text = selected?.DisplayName ?? "Устройство не выбрано";
        UpdateButtons();
    }

    private void UpdateButtons()
    {
#if ANDROID
        var hasSel = selected != null;
        BtnPair.IsEnabled = hasSel && selected!.Bond != BondModel.Bonded;
        BtnConnect.IsEnabled = hasSel && selected!.Bond == BondModel.Bonded
                              && Bt.State != Services.ConnState.Connected;
        BtnForget.IsEnabled = hasSel && selected!.Bond == BondModel.Bonded;
#endif
    }

    private async void OnEnableBluetooth(object? sender, EventArgs e)
    {
#if ANDROID
        if (!Bt.HasAdapter())
        {
            await DisplayAlert("Нет Bluetooth", "Bluetooth-адаптер отсутствует.", "OK");
            return;
        }
        if (!Bt.IsAdapterEnabled()) Bt.EnableAdapter();
        else await DisplayAlert("Bluetooth", "Адаптер уже включён.", "OK");
        await RequestPermissionsIfNeeded();
#else
        await DisplayAlert("Информация", "Функция доступна на Android.", "OK");
#endif
    }

    private async void OnScan(object? sender, EventArgs e)
    {
#if ANDROID
        if (!await RequestPermissionsIfNeeded()) return;
        if (!Bt.IsAdapterEnabled())
        {
            Bt.EnableAdapter();
            await DisplayAlert("Bluetooth выключен",
                "Включите Bluetooth и повторите сканирование.", "OK");
            return;
        }
        devices.Clear();
        selected = null;
        SelectedInfo.Text = "Устройство не выбрано";
        ScanHint.Text = "Сканирование…";
        BtnScan.IsEnabled = false;
        BtnScan.Text = "Поиск…";
        Bt.StartScan();
        // Подсказка при отсутствии найденных устройств (ТЗ 7).
        _ = Task.Delay(15000).ContinueWith(_ => MainThread.InvokeOnMainThreadAsync(() =>
        {
            if (devices.Count == 0)
                ScanHint.Text = "Нет доступных устройств. Убедитесь, что Bluetooth на Arduino включён " +
                                "и модуль находится в режиме поиска.";
        }));
#else
        await DisplayAlert("Информация", "Функция доступна на Android.", "OK");
#endif
    }

    private void OnRefresh(object? sender, EventArgs e)
    {
#if ANDROID
        devices.Clear();
        foreach (var d in Bt.DiscoveredSnapshot()) RefreshItem(d);
        ScanHint.Text = $"Найдено устройств: {devices.Count}";
#endif
    }

    private async void OnPair(object? sender, EventArgs e)
    {
#if ANDROID
        if (selected == null) return;
        if (!await RequestPermissionsIfNeeded()) return;
        var device = Bt.Adapter?.GetRemoteDevice(selected.Address);
        if (device == null)
        {
            await DisplayAlert("Ошибка", "Устройство не найдено.", "OK");
            return;
        }
        Bt.StopScan();
        if (!Bt.Bond(device))
            await DisplayAlert("Ошибка сопряжения",
                "Не удалось начать сопряжение. Проверьте, что устройство включено, и повторите попытку. " +
                "Стандартные PIN-коды: 1234 или 0000.", "OK");
        else
            await DisplayAlert("Сопряжение",
                "Система покажет запрос на подтверждение сопряжения.\n" +
                "Стандартные PIN-коды: 1234, 0000 (можно ввести вручную).", "OK");
#else
        await Task.CompletedTask;
#endif
    }

    private async void OnConnect(object? sender, EventArgs e)
    {
#if ANDROID
        if (selected == null) return;
        if (!await RequestPermissionsIfNeeded()) return;
        Bt.StopScan();
        Bt.ConnectAsync(selected.Address);
        UpdateConnStatus();
#else
        await Task.CompletedTask;
#endif
    }

    private void OnDisconnect(object? sender, EventArgs e)
    {
#if ANDROID
        Bt.Disconnect();
        UpdateConnStatus();
#endif
    }

    private async void OnForget(object? sender, EventArgs e)
    {
#if ANDROID
        if (selected == null) return;
        var device = Bt.Adapter?.GetRemoteDevice(selected.Address);
        if (device == null) return;
        if (Bt.ConnectedDevice?.Address == selected.Address) Bt.Disconnect();
        if (!Bt.Unpair(device))
        {
            // Ограничение публичного API Android (ТЗ 4.1, исключение).
            var go = await DisplayAlert("Удаление сопряжения",
                "Программное удаление сопряжения недоступно через публичный API Android.\n\n" +
                "Открыть системные настройки Bluetooth?", "Да", "Нет");
            if (go) Bt.OpenBluetoothSettings();
        }
        RefreshItem(new Services.BtDeviceInfo(selected.Address, selected.Name, 0, Bond.None));
#else
        await Task.CompletedTask;
#endif
    }

    private void OnAutoReconnectToggled(object? sender, ToggledEventArgs e)
    {
        Preferences.Default.Set("auto_reconnect", e.Value);
#if ANDROID
        Bt.AutoReconnect = e.Value;
#endif
    }

    private async void OnPresets(object? sender, EventArgs e) =>
        await Navigation.PushAsync(new PresetsPage());

    private async void OnLog(object? sender, EventArgs e) =>
        await Navigation.PushAsync(new LogPage());

#if ANDROID
    private async Task<bool> RequestPermissionsIfNeeded()
    {
        var granted = await Services.BtManager.RequestPermissionsAsync();
        if (!granted)
            await DisplayAlert("Разрешения",
                "Для работы с Bluetooth необходимо выдать разрешения приложения.", "OK");
        return granted;
    }
#endif

    protected override async void OnAppearing()
    {
        base.OnAppearing();
        UpdateConnStatus();
#if ANDROID
        // Предложение подключиться к последнему устройству при запуске (ТЗ 4.1.8).
        var last = Bt.LastUsedAddress;
        if (!string.IsNullOrEmpty(last) && Bt.State == Services.ConnState.Disconnected && !askedLast)
        {
            askedLast = true;
            var name = Bt.LastUsedName ?? last;
            var yes = await DisplayAlert("Последнее устройство",
                $"Подключиться к «{name}»?", "Подключить", "Отмена");
            if (yes && await RequestPermissionsIfNeeded()) Bt.ConnectAsync(last!);
        }
#endif
    }
}
