package com.gamepoint.control

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Центральный модуль Bluetooth-связи (ТЗ 4.1, 6).
 * Реализует: управление адаптером, сканирование, сопряжение (createBond +
 * ACTION_BOND_STATE_CHANGED), подключение по SPP, отключение, попытку
 * программного удаления сопряжения, автопереподключение, сохранение
 * последнего устройства.
 */
class BtManager private constructor(private val context: Context) {

    /** Состояние подключения к сокету SPP. */
    enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        fun onAdapterStateChanged(enabled: Boolean) {}
        fun onDeviceFound(device: BluetoothDevice, rssi: Int) {}
        fun onScanFinished() {}
        fun onBondStateChanged(device: BluetoothDevice, state: Int) {}
        fun onConnectionStateChanged(state: ConnState, device: BluetoothDevice?) {}
        fun onDataReceived(line: String) {}
        fun onError(message: String) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()

    val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    @Volatile
    var connectedDevice: BluetoothDevice? = null
        private set

    @Volatile
    var connState: ConnState = ConnState.DISCONNECTED
        private set

    var autoReconnect: Boolean = true

    private var socket: BluetoothSocket? = null
    private var readThread: Thread? = null
    private val connectLock = Any()

    // ---- Ожидание ответа устройства (таймаут 3 с, ТЗ 7) ----
    @Volatile
    private var pendingResponseLatch: CountDownLatch? = null

    @Volatile
    private var lastResponseLine: String? = null

    // ---- Сканирование ----
    @Volatile
    private var scanning = false
    private val discovered = mutableMapOf<String, Int>() // address -> rssi

    private val prefs = context.getSharedPreferences("gpc_bt", Context.MODE_PRIVATE)

    var lastUsedAddress: String?
        get() = prefs.getString(KEY_LAST_DEVICE, null)
        set(v) {
            prefs.edit().putString(KEY_LAST_DEVICE, v).apply()
        }

    var lastUsedName: String?
        get() = prefs.getString(KEY_LAST_DEVICE_NAME, null)
        set(v) {
            prefs.edit().putString(KEY_LAST_DEVICE_NAME, v).apply()
        }

    // ------------------------------------------------------------------
    // Широковещательные приёмники
    // ------------------------------------------------------------------

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    if (device != null) {
                        discovered[device.address] =
                            if (rssi == Short.MIN_VALUE) 0 else rssi.toInt()
                        dispatch { it.onDeviceFound(device, if (rssi == Short.MIN_VALUE) 0 else rssi.toInt()) }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    dispatch { it.onScanFinished() }
                }
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                @Suppress("DEPRECATION")
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val state = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE
                )
                if (device != null) {
                    val name = safeName(device)
                    CommandLog.get(context).info(
                        "Сопряжение ${name} (${device.address}): ${bondStateText(state)}"
                    )
                    dispatch { it.onBondStateChanged(device, state) }
                }
            }
        }
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                val enabled = state == BluetoothAdapter.STATE_ON
                CommandLog.get(context).info("Адаптер Bluetooth: " + when (state) {
                    BluetoothAdapter.STATE_ON -> "включён"
                    BluetoothAdapter.STATE_OFF -> "выключен"
                    BluetoothAdapter.STATE_TURNING_ON -> "включается…"
                    BluetoothAdapter.STATE_TURNING_OFF -> "выключается…"
                    else -> "изменён ($state)"
                })
                if (!enabled && connState != ConnState.DISCONNECTED) {
                    disconnect(notify = true)
                }
                dispatch { it.onAdapterStateChanged(enabled) }
            }
        }
    }

    init {
        val filters = listOf(
            IntentFilter(BluetoothDevice.ACTION_FOUND) to scanReceiver,
            IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) to scanReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED) to bondReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED) to adapterReceiver
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                filters.forEach { (f, r) ->
                    context.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
                }
            } else {
                filters.forEach { (f, r) -> context.registerReceiver(r, f) }
            }
        } catch (e: SecurityException) {
            CommandLog.get(context).error("Не удалось зарегистрировать приёмники: ${e.message}")
        }
    }

    fun addListener(l: Listener) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun dispatch(action: (Listener) -> Unit) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        mainHandler.post { snapshot.forEach(action) }
    }

    // ------------------------------------------------------------------
    // Адаптер
    // ------------------------------------------------------------------

    fun hasAdapter(): Boolean = adapter != null

    fun isAdapterEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    fun enableAdapterIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    // ------------------------------------------------------------------
    // Безопасные обёртки над API (BLUETOOTH_CONNECT / SCAN для API 31+)
    // ------------------------------------------------------------------

    fun safeName(device: BluetoothDevice): String = try {
        device.name ?: ""
    } catch (e: SecurityException) {
        ""
    } catch (e: IllegalArgumentException) {
        ""
    }

    fun safeAlias(device: BluetoothDevice): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            device.alias ?: safeName(device)
        } else {
            safeName(device)
        }
    } catch (e: Exception) {
        safeName(device)
    }

    fun bondStateOf(device: BluetoothDevice): Int = try {
        device.bondState
    } catch (e: SecurityException) {
        BluetoothDevice.BOND_NONE
    }

    // ------------------------------------------------------------------
    // Сканирование (ТЗ 4.1.2)
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScan() {
        val a = adapter
        if (a == null) {
            dispatch { it.onError("Bluetooth-адаптер отсутствует") }
            return
        }
        if (!isAdapterEnabled()) {
            dispatch { it.onError("Bluetooth выключен — включите адаптер") }
            return
        }
        if (!hasScanPermission()) {
            dispatch { it.onError("Нет разрешения на сканирование Bluetooth") }
            return
        }
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            discovered.clear()
            scanning = true
            CommandLog.get(context).info("Сканирование запущено")
            a.startDiscovery()
            // Дополнительно показываем уже сопряжённые устройства.
            try {
                a.bondedDevices.forEach { d ->
                    discovered[d.address] = 0
                    dispatch { l -> l.onDeviceFound(d, 0) }
                }
            } catch (e: SecurityException) {
                CommandLog.get(context).error("Нет доступа к списку сопряжённых: ${e.message}")
            }
        } catch (e: SecurityException) {
            scanning = false
            dispatch { it.onError("Нет разрешения для сканирования: ${e.message}") }
        } catch (e: IOException) {
            scanning = false
            dispatch { it.onError("Ошибка запуска сканирования: ${e.message}") }
        }
    }

    fun isScanning(): Boolean = scanning

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
        } catch (e: SecurityException) {
            /* игнорируем */
        }
        scanning = false
    }

    fun bondedDevices(): Set<BluetoothDevice> = try {
        adapter?.bondedDevices ?: emptySet()
    } catch (e: SecurityException) {
        emptySet()
    }

    fun deviceFromAddress(address: String): BluetoothDevice? =
        try {
            adapter?.getRemoteDevice(address)
        } catch (e: Exception) {
            null
        }

    // ------------------------------------------------------------------
    // Сопряжение (ТЗ 4.1.3)
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun bond(device: BluetoothDevice): Boolean {
        if (bondStateOf(device) == BluetoothDevice.BOND_BONDED) return true
        return try {
            CommandLog.get(context).info("Запуск сопряжения ${safeName(device)} (${device.address})")
            device.createBond()
        } catch (e: SecurityException) {
            dispatch { it.onError("Нет разрешения BLUETOOTH_CONNECT: ${e.message}") }
            false
        } catch (e: Exception) {
            dispatch { it.onError("Ошибка сопряжения: ${e.message}") }
            CommandLog.get(context).error("Ошибка сопряжения: ${e.message}")
            false
        }
    }

    /**
     * Программное удаление сопряжения (ТЗ 4.1.6). Публичного API нет —
     * используем скрытый метод removeBond(); если не получилось, вызывающий
     * код должен показать пояснение и предложить системный экран Bluetooth.
     * @return true, если удаление выполнено программно.
     */
    @SuppressLint("MissingPermission")
    fun unpair(device: BluetoothDevice): Boolean {
        return try {
            val m = BluetoothDevice::class.java.getMethod("removeBond")
            val result = m.invoke(device)
            val ok = result == true
            CommandLog.get(context).info(
                "Программное удаление сопряжения ${safeName(device)}: ${if (ok) "успех" else "не удалось"}"
            )
            ok
        } catch (e: Exception) {
            CommandLog.get(context).error("removeBond недоступен: ${e.message}")
            false
        }
    }

    fun openBluetoothSettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)

    // ------------------------------------------------------------------
    // Подключение по SPP (ТЗ 4.1.4)
    // ------------------------------------------------------------------

    /**
     * Устанавливает SPP-соединение. Метод блокирующий — вызывать из фонового потока.
     * Таймаут подключения ~10 c обеспечивается системой/потоком; connect() выполняется
     * в отдельном потоке с ожиданием до 10 секунд.
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        synchronized(connectLock) {
            if (connState == ConnState.CONNECTED && connectedDevice?.address == device.address) {
                return true
            }
            closeSocketQuietly()
            setConnState(ConnState.CONNECTING, device)
        }

        var sock: BluetoothSocket? = null
        var error: Exception? = null
        val t = Thread {
            try {
                sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                sock!!.inputStream // проверка доступности
                sock!!.connect()
            } catch (e: Exception) {
                error = e
                try {
                    sock?.close()
                } catch (_: Exception) {
                }
                sock = null
            }
        }
        t.start()
        t.join(CONNECT_TIMEOUT_MS)
        if (t.isAlive) {
            // Зависло подключение — закрываем сокет, поток прервётся ошибкой чтения.
            try {
                sock?.close()
            } catch (_: Exception) {
            }
            t.interrupt()
        }

        val finalSocket = sock
        if (finalSocket == null || error != null) {
            val msg = "Не удалось подключиться к ${safeName(device).ifEmpty { device.address }}: " +
                    (error?.message ?: "таймаут подключения")
            setConnState(ConnState.DISCONNECTED, null)
            CommandLog.get(context).error(msg)
            dispatch { it.onError(msg) }
            scheduleReconnect(device)
            return false
        }

        socket = finalSocket
        connectedDevice = device
        lastUsedAddress = device.address
        lastUsedName = safeName(device)
        setConnState(ConnState.CONNECTED, device)
        CommandLog.get(context).info(
            "Подключено: ${safeName(device).ifEmpty { device.address }} (${device.address})"
        )
        startReading(finalSocket)
        return true
    }

    /** Подключение с проверкой разрешений; выполняет фоновый запуск из UI. */
    fun connectAsync(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            dispatch { it.onError("Нет разрешения BLUETOOTH_CONNECT") }
            return
        }
        Thread { connect(device) }.start()
    }

    fun disconnect(notify: Boolean = true) {
        autoReconnectPending.set(false)
        closeSocketQuietly()
        val dev = connectedDevice
        connectedDevice = null
        setConnState(ConnState.DISCONNECTED, null)
        if (notify && dev != null) {
            CommandLog.get(context).info("Отключено от ${safeName(dev).ifEmpty { dev.address }}")
        }
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        readThread?.let { runCatching { it.interrupt() } }
        readThread = null
    }

    // ------------------------------------------------------------------
    // Чтение/запись (протокол раздела 6 ТЗ)
    // ------------------------------------------------------------------

    private fun startReading(sock: BluetoothSocket) {
        val th = Thread {
            val buffer = ByteArray(1024)
            val sb = StringBuilder()
            try {
                val input: InputStream = sock.inputStream
                while (!Thread.currentThread().isInterrupted) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    sb.append(String(buffer, 0, n, StandardCharsets.US_ASCII))
                    var idx: Int
                    while (sb.indexOf("\n").also { idx = it } >= 0) {
                        val line = sb.substring(0, idx).trim()
                        sb.delete(0, idx + 1)
                        if (line.isNotEmpty()) handleIncoming(line)
                    }
                }
            } catch (e: IOException) {
                // Разрыв соединения.
            } finally {
                onSocketLost(sock)
            }
        }
        readThread = th
        th.start()
    }

    private fun handleIncoming(line: String) {
        CommandLog.get(context).rx(line)
        lastResponseLine = line
        pendingResponseLatch?.countDown()
        dispatch { it.onDataReceived(line) }
    }

    private fun onSocketLost(lost: BluetoothSocket) {
        synchronized(connectLock) {
            if (socket !== lost) return // уже переподключились/отключились
            val dev = connectedDevice
            socket = null
            connectedDevice = null
            setConnState(ConnState.DISCONNECTED, null)
            if (dev != null) {
                val msg = "Соединение с ${safeName(dev).ifEmpty { dev.address }} потеряно"
                CommandLog.get(context).error(msg)
                dispatch { it.onError(msg) }
                scheduleReconnect(dev)
            }
        }
    }

    /** Отправка ASCII-команды с завершающим \n. Блокирующая — только из фона. */
    fun sendRaw(command: String): Boolean {
        val sock = socket ?: run {
            dispatch { it.onError("Устройство не подключено") }
            CommandLog.get(context).error("Команда не отправлена (нет соединения): $command")
            return false
        }
        return try {
            val out: OutputStream = sock.outputStream
            out.write((command + "\n").toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            CommandLog.get(context).tx(command.replace("\n", "\\n"))
            true
        } catch (e: IOException) {
            CommandLog.get(context).error("Ошибка отправки «$command»: ${e.message}")
            dispatch { it.onError("Ошибка отправки команды: ${e.message}") }
            false
        }
    }

    /**
     * Отправка команды с ожиданием подтверждения от устройства.
     * Таймаут ожидания ответа — 3 секунды (ТЗ 7). Возвращает строку ответа
     * или null при таймауте/ошибке. Вызывать из фонового потока!
     */
    fun sendAwaitResponse(command: String, timeoutMs: Long = RESPONSE_TIMEOUT_MS): String? {
        val latch = CountDownLatch(1)
        pendingResponseLatch = latch
        lastResponseLine = null
        if (!sendRaw(command)) {
            pendingResponseLatch = null
            return null
        }
        val got = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pendingResponseLatch = null
        return if (got) lastResponseLine else {
            val msg = "Нет ответа от устройства в течение ${timeoutMs / 1000} с (команда: $command)"
            CommandLog.get(context).error(msg)
            dispatch { it.onError(msg) }
            null
        }
    }

    // ------------------------------------------------------------------
    // Команды протокола (раздел 6 ТЗ)
    // ------------------------------------------------------------------

    /** T+float — время удержания WIN_TIME в минутах. */
    fun sendWinTime(minutes: Double): String? =
        sendAwaitResponse("T" + formatFloat(minutes))

    /** D+float — задержка перехвата switchDelay в секундах. */
    fun sendSwitchDelay(seconds: Double): String? =
        sendAwaitResponse("D" + formatFloat(seconds))

    /** I+int — интервал воскрешения аптечки в минутах. */
    fun sendMedkitInterval(minutes: Int): String? =
        sendAwaitResponse("I$minutes")

    /** R — сброс (общая команда для обоих типов устройств). */
    fun sendReset(): String? = sendAwaitResponse("R")

    /** V — запрос версии/типа прошивки (опционально, ТЗ 4.4.3). */
    fun sendQueryVersion(): String? = sendAwaitResponse("V", 3000)

    // ------------------------------------------------------------------
    // Автопереподключение (ТЗ 4.1.4)
    // ------------------------------------------------------------------

    private val autoReconnectPending = AtomicBoolean(false)

    private fun scheduleReconnect(device: BluetoothDevice) {
        if (!autoReconnect) return
        if (!autoReconnectPending.compareAndSet(false, true)) return
        CommandLog.get(context).info("Автопереподключение к ${safeName(device).ifEmpty { device.address }} через 3 с")
        mainHandler.postDelayed({
            autoReconnectPending.set(false)
            if (connState == ConnState.DISCONNECTED && isAdapterEnabled()) {
                if (bondStateOf(device) == BluetoothDevice.BOND_BONDED) {
                    connectAsync(device)
                } else {
                    CommandLog.get(context).error(
                        "Автопереподключение невозможно: устройство не сопряжено"
                    )
                }
            }
        }, RECONNECT_DELAY_MS)
    }

    // ------------------------------------------------------------------
    // Валидация параметров (ТЗ 4.2, 4.3)
    // ------------------------------------------------------------------

    companion object {
        /** Минимальное время удержания, минут. */
        const val MIN_WIN_TIME = 0.05

        @Volatile
        private var instance: BtManager? = null

        /** Единый экземпляр Bluetooth-модуля на всё приложение. */
        fun get(context: Context): BtManager =
            instance ?: synchronized(this) {
                instance ?: BtManager(context.applicationContext).also { instance = it }
            }

        /** Рекомендуемый диапазон switchDelay, секунд. */
        const val RECOMMENDED_DELAY_MIN = 30.0
        const val RECOMMENDED_DELAY_MAX = 90.0

        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val RESPONSE_TIMEOUT_MS = 3000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val RECONNECT_DELAY_MS = 3000L
        private const val KEY_LAST_DEVICE = "last_device"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"

        fun validateWinTime(minutes: Double): Boolean = minutes > MIN_WIN_TIME

        fun validateSwitchDelay(seconds: Double): Boolean = seconds >= 0.0

        fun validateMedkitInterval(minutes: Int): Boolean = minutes > 0

        /** Формат числа для Arduino parseFloat: точка как разделитель дробной части. */
        fun formatFloat(v: Double): String {
            return if (v == v.toLong().toDouble()) {
                v.toLong().toString()
            } else {
                String.format(java.util.Locale.US, "%.2f", v)
            }
        }

        fun bondStateText(state: Int): String = when (state) {
            BluetoothDevice.BOND_BONDED -> "сопряжено"
            BluetoothDevice.BOND_BONDING -> "сопряжение…"
            else -> "не сопряжено"
        }
    }

    private fun setConnState(state: ConnState, device: BluetoothDevice?) {
        connState = state
        dispatch { it.onConnectionStateChanged(state, device) }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermissionSafely(android.Manifest.permission.BLUETOOTH_SCAN)
        } else {
            true
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermissionSafely(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
    }

    /** Требуемые runtime-разрешения для текущей версии Android (ТЗ 8). */
    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Для API 23–30 сканирование требует ACCESS_FINE_LOCATION.
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            emptyArray()
        }
    }

    /** Освобождение ресурсов (вызывается при уничтожении процесса/активности). */
    fun shutdown() {
        stopScan()
        disconnect(notify = false)
        try {
            context.unregisterReceiver(scanReceiver)
            context.unregisterReceiver(bondReceiver)
            context.unregisterReceiver(adapterReceiver)
        } catch (_: Exception) {
        }
    }

    private fun checkSelfPermissionSafely(permission: String): Boolean =
        try {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
}
