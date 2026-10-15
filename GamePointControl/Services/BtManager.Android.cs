#if ANDROID
using System.Text;
using Android.Bluetooth;
using Android.Content;
using Java.Interop;
using GamePointControl.Models;

namespace GamePointControl.Services;

/// <summary>Состояние SPP-подключения (ТЗ 4.1).</summary>
public enum ConnState { Disconnected, Connecting, Connected }

/// <summary>Запись в списке устройств.</summary>
public sealed record BtDeviceInfo(string Address, string Name, int Rssi, Bond BondState)
{
    public string DisplayName => string.IsNullOrWhiteSpace(Name) ? Address : $"{Name} ({Address})";
}

/// <summary>
/// Центральный Bluetooth-модуль (ТЗ 4.1, 6): адаптер, сканирование,
/// сопряжение (CreateBond + ACTION_BOND_STATE_CHANGED), SPP-подключение,
/// отключение, программное удаление сопряжения, автопереподключение.
/// Реализация на нативных Android API через .NET for Android bindings.
/// </summary>
public sealed class BtManager : Java.Lang.Object
{

    // ---- Протокол (раздел 6 ТЗ) ----
    public static readonly Java.Util.UUID SppUuid = Java.Util.UUID.FromString("00001101-0000-1000-8000-00805F9B34FB")!;
    public const long ResponseTimeoutMs = 3000;
    public const long ConnectTimeoutMs = 10000;
    public const long ReconnectDelayMs = 3000;

    // Валидация параметров (ТЗ 4.2, 4.3)
    public const double MinWinTime = 0.05;               // минут
    public const double RecommendedDelayMin = 30.0;      // секунд
    public const double RecommendedDelayMax = 90.0;

    public static bool ValidateWinTime(double minutes) => minutes > MinWinTime;
    public static bool ValidateSwitchDelay(double seconds) => seconds >= 0.0;
    public static bool ValidateMedkitInterval(int minutes) => minutes > 0;

    /// <summary>Формат числа для Arduino parseFloat: точка как разделитель.</summary>
    public static string FormatFloat(double v) =>
        v == Math.Floor(v)
            ? ((long)v).ToString(System.Globalization.CultureInfo.InvariantCulture)
            : v.ToString("0.00", System.Globalization.CultureInfo.InvariantCulture);

    // ---- Синглтон ----
    private static BtManager? instance;
    public static BtManager Current => instance ??= new BtManager();

    private readonly Context appContext;
    private readonly BluetoothManager? btManager;
    public BluetoothAdapter? Adapter => btManager?.Adapter;

    public event Action<bool>? AdapterStateChanged;
    public event Action<BtDeviceInfo>? DeviceFound;
    public event Action? ScanFinished;
    public event Action<BtDeviceInfo>? BondStateChanged;
    public event Action<ConnState, BtDeviceInfo?>? ConnectionStateChanged;
    public event Action<string>? DataReceived;
    public event Action<string>? ErrorOccurred;

    public volatile ConnState State = ConnState.Disconnected;
    public BtDeviceInfo? ConnectedDevice { get; private set; }
    public bool AutoReconnect { get; set; } = true;

    private BluetoothSocket? socket;
    private Thread? readThread;
    private readonly object connectLock = new();

    // Ожидание ответа устройства (таймаут 3 с, ТЗ 7)
    private TaskCompletionSource<string>? responseTcs;
    private string? lastResponseLine;

    // Сканирование
    private bool scanning;
    private readonly Dictionary<string, BtDeviceInfo> discovered = new();

    // Сохранение последнего устройства (ТЗ 4.1.8)
    public string? LastUsedAddress
    {
        get => Preferences.Default.Get("last_device", null);
        set => Preferences.Default.Set("last_device", value);
    }

    public string? LastUsedName
    {
        get => Preferences.Default.Get("last_device_name", null);
        set => Preferences.Default.Set("last_device_name", value);
    }

    private BroadcastReceiver? scanReceiver;
    private BroadcastReceiver? bondReceiver;
    private BroadcastReceiver? adapterReceiver;

    private BtManager()
    {
        appContext = Microsoft.Maui.Storage.Platform.AppContext;
        btManager = (BluetoothManager?)appContext.GetSystemService(Context.BluetoothService);
        RegisterReceivers();
    }

    // ------------------------------------------------------------------
    // Разрешения (ТЗ раздел 8)
    // ------------------------------------------------------------------

    /// <summary>Требуемые runtime-разрешения для текущей версии Android.</summary>
    public static string[] RequiredPermissions =>
        OperatingSystem.IsAndroidVersionAtLeast(31)
            ? new[]
            {
                Android.Manifest.Permission.BluetoothScan!,
                Android.Manifest.Permission.BluetoothConnect!
            }
            : new[]
            {
                Android.Manifest.Permission.AccessFineLocation!
            };

    public static bool HasPermission(string permission) =>
        Permissions.CheckStatusAsync(permission).GetAwaiter().GetResult() == PermissionStatus.Granted;

    public static async Task<bool> RequestPermissionsAsync()
    {
        foreach (var p in RequiredPermissions)
        {
            var s = await Permissions.CheckStatusAsync(p);
            if (s != PermissionStatus.Granted)
                s = await Permissions.RequestAsync(p);
            if (s != PermissionStatus.Granted) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Адаптер (ТЗ 4.1.1)
    // ------------------------------------------------------------------

    public bool HasAdapter() => Adapter != null;

    public bool IsAdapterEnabled()
    {
        try { return Adapter?.IsEnabled == true; }
        catch (Java.Security.SecurityException) { return false; }
    }

    /// <summary>Запрос включения Bluetooth из приложения.</summary>
    public void EnableAdapter()
    {
        try
        {
            var intent = new Intent(BluetoothAdapter.ActionRequestEnable);
            intent.AddFlags(ActivityFlags.NewTask);
            appContext.StartActivity(intent);
        }
        catch (Java.Lang.Exception e)
        {
            DispatchError("Не удалось запросить включение Bluetooth: " + e.Message);
        }
    }

    // ------------------------------------------------------------------
    // Широковещательные приёмники
    // ------------------------------------------------------------------

    private void RegisterReceivers()
    {
        scanReceiver = new InnerReceiver((_, intent) =>
        {
            switch (intent?.Action)
            {
                case BluetoothDevice.ActionFound:
                {
                    var device = intent.GetParcelableExtra(BluetoothDevice.ExtraDevice) as BluetoothDevice;
                    var rssiArr = intent.GetShortArrayExtra(BluetoothDevice.ExtraRssi);
                    var rssi = rssiArr is { Length: > 0 } ? rssiArr[0] : (short)0;
                    if (device != null) ReportFound(device, rssi);
                    break;
                }
                case BluetoothAdapter.ActionDiscoveryFinished:
                    scanning = false;
                    ScanFinished?.Invoke();
                    break;
            }
        });

        bondReceiver = new InnerReceiver((_, intent) =>
        {
            if (intent?.Action != BluetoothDevice.ActionBondStateChanged) return;
            var device = intent.GetParcelableExtra(BluetoothDevice.ExtraDevice) as BluetoothDevice;
            var state = (Bond)intent.GetIntExtra(BluetoothDevice.ExtraBondState, (int)Bond.None);
            if (device == null) return;
            var info = ToInfo(device, 0);
            CommandLog.Current.Info($"Сопряжение {info.Name} ({info.Address}): {BondStateText(state)}");
            BondStateChanged?.Invoke(info);
            DeviceFound?.Invoke(info);
        });

        adapterReceiver = new InnerReceiver((_, intent) =>
        {
            if (intent?.Action != BluetoothAdapter.ActionStateChanged) return;
            var state = (BluetoothState)intent.GetIntExtra(BluetoothAdapter.ExtraState, -1);
            CommandLog.Current.Info("Адаптер Bluetooth: " + state switch
            {
                BluetoothState.On => "включён",
                BluetoothState.Off => "выключен",
                BluetoothState.TurningOn => "включается…",
                BluetoothState.TurningOff => "выключается…",
                _ => $"изменён ({state})",
            });
            var enabled = state == BluetoothState.On;
            if (!enabled && State != ConnState.Disconnected) Disconnect(notify: true);
            AdapterStateChanged?.Invoke(enabled);
        });

        try
        {
            appContext.RegisterReceiver(scanReceiver, new IntentFilter(BluetoothDevice.ActionFound));
            appContext.RegisterReceiver(scanReceiver, new IntentFilter(BluetoothAdapter.ActionDiscoveryFinished));
            appContext.RegisterReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ActionBondStateChanged));
            appContext.RegisterReceiver(adapterReceiver, new IntentFilter(BluetoothAdapter.ActionStateChanged));
        }
        catch (Java.Lang.SecurityException e)
        {
            CommandLog.Current.Error("Не удалось зарегистрировать приёмники: " + e.Message);
        }
    }

    private sealed class InnerReceiver : BroadcastReceiver
    {
        private readonly Action<Android.Content.Context?, Intent?> handler;
        public InnerReceiver(Action<Android.Content.Context?, Intent?> handler) => this.handler = handler;
        public override void OnReceive(Android.Content.Context? context, Intent? intent) => handler(context, intent);
    }

    // ------------------------------------------------------------------
    // Сканирование (ТЗ 4.1.2)
    // ------------------------------------------------------------------

    public void StartScan()
    {
        var a = Adapter;
        if (a == null) { DispatchError("Bluetooth-адаптер отсутствует"); return; }
        if (!IsAdapterEnabled()) { DispatchError("Bluetooth выключен — включите адаптер"); return; }
        if (!HasPermission(Android.Manifest.Permission.BluetoothScan!) &&
            !HasPermission(Android.Manifest.Permission.AccessFineLocation!))
        {
            DispatchError("Нет разрешения на сканирование Bluetooth");
            return;
        }

        try
        {
            if (a.IsDiscovering) a.CancelDiscovery();
            lock (discovered) discovered.Clear();
            scanning = true;
            CommandLog.Current.Info("Сканирование запущено");

            if (OperatingSystem.IsAndroidVersionAtLeast(31))
            {
                try
                {
                    a.StartDiscovery();
                }
                catch (SecurityException)
                {
                    DispatchError("Нет разрешения BLUETOOTH_SCAN");
                    scanning = false;
                    return;
                }
            }
            else
            {
#pragma warning disable CA1422
                a.StartDiscovery();
#pragma warning restore CA1422
            }

            // Дополнительно показываем уже сопряжённые устройства.
            try
            {
                foreach (var d in a.BondedDevices ?? Enumerable.Empty<BluetoothDevice>())
                    ReportFound(d, 0);
            }
            catch (SecurityException)
            {
                CommandLog.Current.Error("Нет доступа к списку сопряжённых устройств");
            }
        }
        catch (Java.Lang.SecurityException e)
        {
            scanning = false;
            DispatchError("Нет разрешения для сканирования: " + e.Message);
        }
        catch (Java.IO.IOException e)
        {
            scanning = false;
            DispatchError("Ошибка запуска сканирования: " + e.Message);
        }
    }

    public bool IsScanning() => scanning;

    public void StopScan()
    {
        try
        {
            var a = Adapter;
            if (a?.IsDiscovering == true) a.CancelDiscovery();
        }
        catch (Java.Lang.SecurityException) { /* игнорируем */ }
        scanning = false;
    }

    private void ReportFound(BluetoothDevice device, short rssi)
    {
        var info = ToInfo(device, rssi);
        lock (discovered) discovered[info.Address] = info;
        DeviceFound?.Invoke(info);
    }

    public List<BtDeviceInfo> DiscoveredSnapshot()
    {
        lock (discovered) return discovered.Values.ToList();
    }

    public BtDeviceInfo? DeviceFromAddress(string address)
    {
        try
        {
            var d = Adapter?.GetRemoteDevice(address);
            return d != null ? ToInfo(d, 0) : null;
        }
        catch (Java.Lang.Exception) { return null; }
    }

    public BtDeviceInfo ToInfo(BluetoothDevice device, int rssi) =>
        new(device.Address ?? "", SafeName(device), rssi, SafeBondState(device));

    public static string SafeName(BluetoothDevice device)
    {
        try { return device.Name ?? ""; }
        catch (Java.Lang.SecurityException) { return ""; }
        catch (Java.Lang.IllegalArgumentException) { return ""; }
    }

    public static Bond SafeBondState(BluetoothDevice device)
    {
        try { return device.BondState; }
        catch (Java.Lang.SecurityException) { return Bond.None; }
    }

    public static string BondStateText(Bond b) => b switch
    {
        Bond.Bonded => "сопряжено",
        Bond.Bonding => "сопряжение…",
        _ => "не сопряжено",
    };

    // ------------------------------------------------------------------
    // Сопряжение (ТЗ 4.1.3)
    // ------------------------------------------------------------------

    public bool Bond(BluetoothDevice device)
    {
        if (SafeBondState(device) == Bond.Bonded) return true;
        try
        {
            CommandLog.Current.Info($"Запуск сопряжения {SafeName(device)} ({device.Address})");
            return device.CreateBond();
        }
        catch (Java.Lang.SecurityException e)
        {
            DispatchError("Нет разрешения BLUETOOTH_CONNECT: " + e.Message);
            return false;
        }
        catch (Java.Lang.Exception e)
        {
            CommandLog.Current.Error("Ошибка сопряжения: " + e.Message);
            DispatchError("Ошибка сопряжения: " + e.Message);
            return false;
        }
    }

    /// <summary>
    /// Программное удаление сопряжения (ТЗ 4.1.6). Публичного API нет —
    /// вызываем скрытый метод removeBond() рефлексией; если не удалось,
    /// вызывающий код показывает пояснение и открывает системный экран Bluetooth.
    /// </summary>
    public bool Unpair(BluetoothDevice device)
    {
        try
        {
            var env = Android.Runtime.AndroidEnvironment.CurrentJNIEnv;
            var cls = device.ClassHandle.Handle;
            var mid = JNIEnv.GetMethodID(cls, "removeBond", "()Z");
            var ok = JNIEnv.CallBooleanMethod(device.Handle, mid);
            CommandLog.Current.Info(
                $"Программное удаление сопряжения {SafeName(device)}: {(ok ? "успех" : "не удалось")}");
            return ok;
        }
        catch (Java.Lang.Exception e)
        {
            CommandLog.Current.Error("removeBond недоступен: " + e.Message);
            return false;
        }
    }

    /// <summary>Переход на системный экран Bluetooth (исключение из ТЗ 4.1).</summary>
    public void OpenBluetoothSettings()
    {
        try
        {
            var intent = new Intent(Android.Provider.Settings.ActionBluetoothSettings);
            intent.AddFlags(ActivityFlags.NewTask);
            appContext.StartActivity(intent);
        }
        catch (Java.Lang.Exception e)
        {
            DispatchError("Не удалось открыть системные настройки: " + e.Message);
        }
    }

    // ------------------------------------------------------------------
    // Подключение по SPP (ТЗ 4.1.4)
    // ------------------------------------------------------------------

    /// <summary>Блокирующий SPP-connect — вызывать из фонового потока.</summary>
    public bool Connect(BluetoothDevice device)
    {
        lock (connectLock)
        {
            if (State == ConnState.Connected && ConnectedDevice?.Address == device.Address)
                return true;
            CloseSocketQuietly();
            SetState(ConnState.Connecting, ToInfo(device, 0));
        }

        BluetoothSocket? sock = null;
        Exception? error = null;
        var t = new Thread(() =>
        {
            try
            {
                sock = device.CreateRfcommSocketToServiceRecord(SppUuid);
                _ = sock.InputStream; // проверка доступности
                sock.Connect();
            }
            catch (Exception e)
            {
                error = e;
                try { sock?.Close(); } catch { /* ignore */ }
                sock = null;
            }
        }, "SPP-Connect");
        t.Start();
        t.Join((int)ConnectTimeoutMs);
        if (t.IsAlive)
        {
            try { sock?.Close(); } catch { /* ignore */ }
            t.Interrupt();
        }

        if (sock == null || error != null)
        {
            var msg = $"Не удалось подключиться к {SafeName(device)} ({device.Address}): " +
                      (error?.Message ?? "таймаут подключения");
            SetState(ConnState.Disconnected, null);
            CommandLog.Current.Error(msg);
            DispatchError(msg);
            ScheduleReconnect(device);
            return false;
        }

        socket = sock;
        ConnectedDevice = ToInfo(device, 0);
        LastUsedAddress = device.Address;
        LastUsedName = SafeName(device);
        SetState(ConnState.Connected, ConnectedDevice);
        CommandLog.Current.Info($"Подключено: {ConnectedDevice.DisplayName}");
        StartReading(sock);
        return true;
    }

    /// <summary>Подключение с проверкой разрешений; выполняется в фоновом потоке.</summary>
    public void ConnectAsync(string address)
    {
        if (!HasPermission(Android.Manifest.Permission.BluetoothConnect!))
        {
            DispatchError("Нет разрешения BLUETOOTH_CONNECT");
            return;
        }
        var device = Adapter?.GetRemoteDevice(address);
        if (device == null)
        {
            DispatchError("Устройство не найдено: " + address);
            return;
        }
        new Thread(() => Connect(device), "SPP-Connect-Outer").Start();
    }

    public void Disconnect(bool notify = true)
    {
        reconnectPending = false;
        CloseSocketQuietly();
        var dev = ConnectedDevice;
        ConnectedDevice = null;
        SetState(ConnState.Disconnected, null);
        if (notify && dev != null)
            CommandLog.Current.Info($"Отключено от {dev.DisplayName}");
    }

    private void CloseSocketQuietly()
    {
        try { socket?.Close(); } catch { /* ignore */ }
        socket = null;
        try { readThread?.Interrupt(); } catch { /* ignore */ }
        readThread = null;
    }

    // ------------------------------------------------------------------
    // Чтение/запись (протокол раздела 6 ТЗ)
    // ------------------------------------------------------------------

    private void StartReading(BluetoothSocket sock)
    {
        readThread = new Thread(() =>
        {
            var buffer = new byte[1024];
            var sb = new StringBuilder();
            try
            {
                var input = sock.InputStream;
                while (!Thread.CurrentThread().IsInterrupted())
                {
                    var n = input.Read(buffer);
                    if (n <= 0) break;
                    sb.Append(Encoding.ASCII.GetString(buffer, 0, n));
                    int idx;
                    while ((idx = sb.ToString().IndexOf('\n')) >= 0)
                    {
                        var line = sb.ToString(0, idx).Trim();
                        sb.Remove(0, idx + 1);
                        if (line.Length > 0) HandleIncoming(line);
                    }
                }
            }
            catch (Java.IO.IOException) { /* разрыв соединения */ }
            catch (Exception) { /* разрыв соединения */ }
            finally { OnSocketLost(sock); }
        }, "SPP-Read");
        readThread.IsBackground = true;
        readThread.Start();
    }

    private void HandleIncoming(string line)
    {
        CommandLog.Current.Rx(line);
        lastResponseLine = line;
        responseTcs?.TrySetResult(line);
        MainThread.BeginInvokeOnMainThread(() => DataReceived?.Invoke(line));
    }

    private void OnSocketLost(BluetoothSocket lost)
    {
        lock (connectLock)
        {
            if (!ReferenceEquals(socket, lost)) return; // уже переподключились/отключились
            var dev = ConnectedDevice;
            socket = null;
            ConnectedDevice = null;
            SetState(ConnState.Disconnected, null);
            if (dev != null)
            {
                var msg = $"Соединение с {dev.DisplayName} потеряно";
                CommandLog.Current.Error(msg);
                DispatchError(msg);
                var device = Adapter?.GetRemoteDevice(dev.Address);
                if (device != null) ScheduleReconnect(device);
            }
        }
    }

    /// <summary>Отправка ASCII-команды с завершающим \n. Блокирующая — только из фона.</summary>
    public bool SendRaw(string command)
    {
        var sock = socket;
        if (sock == null)
        {
            CommandLog.Current.Error($"Команда не отправлена (нет соединения): {command}");
            DispatchError("Устройство не подключено");
            return false;
        }
        try
        {
            var outStream = sock.OutputStream;
            var bytes = Encoding.ASCII.GetBytes(command + "\n");
            outStream.Write(bytes, 0, bytes.Length);
            outStream.Flush();
            CommandLog.Current.Tx(command);
            return true;
        }
        catch (Java.IO.IOException e)
        {
            CommandLog.Current.Error($"Ошибка отправки «{command}»: {e.Message}");
            DispatchError("Ошибка отправки команды: " + e.Message);
            return false;
        }
    }

    /// <summary>
    /// Отправка команды с ожиданием подтверждения (таймаут 3 с, ТЗ 7).
    /// Возвращает строку ответа или null при таймауте/ошибке. Только из фона.
    /// </summary>
    public string? SendAwaitResponse(string command, long timeoutMs = ResponseTimeoutMs)
    {
        var tcs = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        responseTcs = tcs;
        lastResponseLine = null;
        if (!SendRaw(command))
        {
            responseTcs = null;
            return null;
        }
        var done = tcs.Task.Wait((int)timeoutMs);
        responseTcs = null;
        if (done) return lastResponseLine;
        var msg = $"Нет ответа от устройства в течение {timeoutMs / 1000} с (команда: {command})";
        CommandLog.Current.Error(msg);
        DispatchError(msg);
        return null;
    }

    // ------------------------------------------------------------------
    // Команды протокола (раздел 6 ТЗ)
    // ------------------------------------------------------------------

    /// <summary>T+float — время удержания WIN_TIME в минутах. Пример: T1.5\n</summary>
    public string? SendWinTime(double minutes) => SendAwaitResponse("T" + FormatFloat(minutes));

    /// <summary>D+float — задержка перехвата switchDelay в секундах. Пример: D45\n</summary>
    public string? SendSwitchDelay(double seconds) => SendAwaitResponse("D" + FormatFloat(seconds));

    /// <summary>I+int — интервал воскрешения аптечки (минуты). Пример: I10\n</summary>
    public string? SendMedkitInterval(int minutes) => SendAwaitResponse("I" + minutes);

    /// <summary>R — сброс (общая команда для обоих типов устройств).</summary>
    public string? SendReset() => SendAwaitResponse("R");

    /// <summary>V — запрос версии/типа прошивки (опционально, ТЗ 4.4.3).</summary>
    public string? SendQueryVersion() => SendAwaitResponse("V", ResponseTimeoutMs);

    // ------------------------------------------------------------------
    // Автопереподключение (ТЗ 4.1.4)
    // ------------------------------------------------------------------

    private volatile bool reconnectPending;

    private void ScheduleReconnect(BluetoothDevice device)
    {
        if (!AutoReconnect) return;
        if (reconnectPending) return;
        reconnectPending = true;
        CommandLog.Current.Info(
            $"Автопереподключение к {SafeName(device)} ({device.Address}) через {ReconnectDelayMs / 1000} с");
        MainThread.BeginInvokeOnMainThread(async () =>
        {
            await Task.Delay((int)ReconnectDelayMs);
            reconnectPending = false;
            if (State != ConnState.Disconnected || !IsAdapterEnabled()) return;
            if (SafeBondState(device) == Bond.Bonded)
                ConnectAsync(device.Address ?? "");
            else
                CommandLog.Current.Error("Автопереподключение невозможно: устройство не сопряжено");
        });
    }

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

    private void SetState(ConnState state, BtDeviceInfo? device)
    {
        State = state;
        MainThread.BeginInvokeOnMainThread(() => ConnectionStateChanged?.Invoke(state, device));
    }

    private void DispatchError(string message) =>
        MainThread.BeginInvokeOnMainThread(() => ErrorOccurred?.Invoke(message));

    /** Освобождение ресурсов. */
    public void Shutdown()
    {
        StopScan();
        Disconnect(notify: false);
        try
        {
            if (scanReceiver != null) appContext.UnregisterReceiver(scanReceiver);
            if (bondReceiver != null) appContext.UnregisterReceiver(bondReceiver);
            if (adapterReceiver != null) appContext.UnregisterReceiver(adapterReceiver);
        }
        catch (Java.Lang.Exception) { /* ignore */ }
    }
}
#endif
