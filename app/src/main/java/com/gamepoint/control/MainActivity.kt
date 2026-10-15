package com.gamepoint.control

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar

/**
 * Главный экран (ТЗ 5.2): включение Bluetooth, сканирование, список устройств
 * с индикацией состояния «Не сопряжено / Сопряжено / Подключено», кнопки
 * «Сопряжение», «Подключить», «Отключить», «Забыть».
 */
class MainActivity : AppCompatActivity(), BtManager.Listener {

    private lateinit var bt: BtManager
    private lateinit var adapterView: DeviceAdapter
    private lateinit var tvStatus: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnScan: Button
    private lateinit var btnEnable: Button
    private lateinit var rv: RecyclerView

    private val devices = LinkedHashMap<String, Item>() // address -> item
    private var selectedAddress: String? = null

    private data class Item(
        val device: BluetoothDevice,
        var name: String,
        var rssi: Int,
        var isNew: Boolean
    )

    private lateinit var enableLauncher: ActivityResultLauncher<Intent>
    private lateinit var permsLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setTitle(R.string.main_title)

        bt = BtManager.get(this)
        bt.addListener(this)

        tvStatus = findViewById(R.id.tv_connection_status)
        tvEmpty = findViewById(R.id.tv_empty)
        btnScan = findViewById(R.id.btn_scan)
        btnEnable = findViewById(R.id.btn_enable_bt)
        rv = findViewById(R.id.rv_devices)

        adapterView = DeviceAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rv.adapter = adapterView

        enableLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                CommandLog.get(this).info("Bluetooth включён пользователем")
                checkPermissionsThenScan()
            } else {
                toast(R.string.err_bt_not_enabled)
                CommandLog.get(this).error("Пользователь отклонил включение Bluetooth")
            }
            refreshUi()
        }

        permsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val denied = grants.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                toast(getString(R.string.err_perms_denied, denied.joinToString(", ") { it.substringAfterLast('.') }))
                CommandLog.get(this).error("Разрешения отклонены: $denied")
            }
            refreshUi()
        }

        btnEnable.setOnClickListener { requestEnableBluetooth() }
        btnScan.setOnClickListener { checkPermissionsThenScan() }

        refreshUi()
        offerReconnectToLastDevice()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        bt.removeListener(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Разрешения и адаптер
    // ------------------------------------------------------------------

    private fun checkPermissionsThenScan() {
        val needed = bt.requiredPermissions().filter { p ->
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, p
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permsLauncher.launch(needed.toTypedArray())
            return
        }
        if (!bt.isAdapterEnabled()) {
            requestEnableBluetooth()
            return
        }
        startScan()
    }

    private fun requestEnableBluetooth() {
        if (!bt.hasAdapter()) {
            toast(R.string.err_no_bt_adapter)
            return
        }
        if (bt.isAdapterEnabled()) {
            toast(R.string.bt_already_on)
            return
        }
        try {
            enableLauncher.launch(bt.enableAdapterIntent())
        } catch (e: Exception) {
            toast(R.string.err_cannot_request_enable)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        devices.clear()
        adapterView.notifyDataSetChanged()
        bt.startScan()
        btnScan.isEnabled = false
        btnScan.text = getString(R.string.scanning)
    }

    // ------------------------------------------------------------------
    // Предложение подключиться к последнему устройству (ТЗ 4.1.8)
    // ------------------------------------------------------------------

    private fun offerReconnectToLastDevice() {
        val addr = bt.lastUsedAddress ?: return
        if (bt.connState == BtManager.ConnState.CONNECTED) return
        val dev = bt.deviceFromAddress(addr) ?: return
        val name = bt.lastUsedName ?: dev.address
        AlertDialog.Builder(this)
            .setTitle(R.string.reconnect_title)
            .setMessage(getString(R.string.reconnect_message, name))
            .setPositiveButton(R.string.connect) { _, _ -> connectToDevice(dev) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Действия над устройствами
    // ------------------------------------------------------------------

    private fun connectToDevice(dev: BluetoothDevice) {
        if (bt.bondStateOf(dev) != BluetoothDevice.BOND_BONDED) {
            // Сначала сопряжение — подключение выполним по его завершении.
            pendingConnectAddress = dev.address
            if (!bt.bond(dev)) {
                toast(R.string.err_bond_failed)
            } else {
                toast(R.string.bond_started)
            }
            return
        }
        bt.stopScan() // сканирование мешает подключению
        autoOpenOnConnect = true
        bt.connectAsync(dev)
    }

    private var pendingConnectAddress: String? = null

    private fun onBonded(dev: BluetoothDevice) {
        val pending = pendingConnectAddress
        if (pending == dev.address) {
            pendingConnectAddress = null
            bt.stopScan()
            autoOpenOnConnect = true
            bt.connectAsync(dev)
        }
        refreshListStates()
    }

    private fun bondSelected(dev: BluetoothDevice) {
        if (bt.bondStateOf(dev) == BluetoothDevice.BOND_BONDED) {
            toast(R.string.already_bonded)
            return
        }
        if (bt.bond(dev)) toast(R.string.bond_started) else toast(R.string.err_bond_failed)
    }

    private fun forgetDevice(dev: BluetoothDevice) {
        AlertDialog.Builder(this)
            .setTitle(R.string.forget_title)
            .setMessage(getString(R.string.forget_message, displayName(dev)))
            .setPositiveButton(R.string.ok) { _, _ ->
                if (bt.connState == BtManager.ConnState.CONNECTED &&
                    bt.connectedDevice?.address == dev.address
                ) {
                    bt.disconnect()
                }
                if (!bt.unpair(dev)) {
                    // API не позволяет программно — пояснение + переход в настройки (ТЗ 4.1.6)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.unpair_fallback_title)
                        .setMessage(R.string.unpair_fallback_message)
                        .setPositiveButton(R.string.open_settings) { _, _ ->
                            try {
                                startActivity(bt.openBluetoothSettingsIntent())
                            } catch (e: Exception) {
                                toast(R.string.err_open_settings)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    toast(R.string.unpair_done)
                }
                refreshListStates()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDeviceScreen(dev: BluetoothDevice) {
        val intent = Intent(this, DeviceActivity::class.java).apply {
            putExtra(DeviceActivity.EXTRA_ADDRESS, dev.address)
        }
        startActivity(intent)
    }

    private fun displayName(dev: BluetoothDevice): String {
        val n = bt.safeAlias(dev)
        return if (n.isEmpty()) dev.address else n
    }

    // ------------------------------------------------------------------
    // Обновление UI
    // ------------------------------------------------------------------

    private fun refreshUi() {
        val enabled = bt.isAdapterEnabled()
        btnEnable.visibility = if (enabled || !bt.hasAdapter()) View.GONE else View.VISIBLE
        btnScan.isEnabled = enabled
        when (bt.connState) {
            BtManager.ConnState.CONNECTED -> {
                val d = bt.connectedDevice
                tvStatus.text = getString(
                    R.string.status_connected,
                    d?.let { displayName(it) } ?: "",
                    d?.address ?: ""
                )
                tvStatus.setTextColor(getColor(R.color.ok_green))
            }
            BtManager.ConnState.CONNECTING -> {
                tvStatus.text = R.string.status_connecting
                tvStatus.setTextColor(getColor(R.color.warn_orange))
            }
            BtManager.ConnState.DISCONNECTED -> {
                tvStatus.text = if (!bt.hasAdapter()) {
                    R.string.status_no_adapter
                } else if (!enabled) {
                    R.string.status_bt_off
                } else {
                    R.string.status_disconnected
                }
                tvStatus.setTextColor(getColor(R.color.error_red))
            }
        }
        refreshListStates()
    }

    private fun refreshListStates() {
        runOnUiThread {
            adapterView.notifyItemRangeChanged(0, devices.size)
            tvEmpty.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------------
    // Callbacks BtManager.Listener
    // ------------------------------------------------------------------

    override fun onAdapterStateChanged(enabled: Boolean) = refreshUi()

    override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {
        val key = device.address
        val existing = devices[key]
        if (existing != null) {
            existing.rssi = rssi
            runOnUiThread { adapterView.notifyDataSetChanged() }
        } else {
            devices[key] = Item(device, displayName(device), rssi, true)
            runOnUiThread {
                adapterView.notifyItemInserted(devices.size - 1)
                tvEmpty.visibility = View.GONE
            }
        }
    }

    override fun onScanFinished() {
        runOnUiThread {
            btnScan.isEnabled = bt.isAdapterEnabled()
            btnScan.text = getString(R.string.scan)
            if (devices.isEmpty()) {
                Snackbar.make(
                    rv, R.string.hint_no_devices, Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onBondStateChanged(device: BluetoothDevice, state: Int) {
        if (state == BluetoothDevice.BOND_BONDED) {
            onBonded(device)
            toast(R.string.bond_success)
        } else if (state == BluetoothDevice.BOND_NONE) {
            // Неудача сопряжения или снятие сопряжения.
            pendingConnectAddress = null
        }
        onDeviceFound(device, devices[device.address]?.rssi ?: 0)
        refreshListStates()
    }

    private var autoOpenOnConnect = false

    override fun onConnectionStateChanged(state: BtManager.ConnState, device: BluetoothDevice?) {
        refreshUi()
        if (state == BtManager.ConnState.CONNECTED && device != null && autoOpenOnConnect) {
            // Переход на экран устройства — только если пользователь сам инициировал
            // подключение, чтобы не выдёргивать его при автопереподключении.
            autoOpenOnConnect = false
            openDeviceScreen(device)
        }
    }

    override fun onDataReceived(line: String) {
        // Ответы отображаются на экране устройства; здесь только обновляем список.
    }

    override fun onError(message: String) {
        Snackbar.make(rv, message, Snackbar.LENGTH_LONG).show()
        if (bt.connState == BtManager.ConnState.DISCONNECTED) refreshUi()
    }

    // ------------------------------------------------------------------
    // Меню
    // ------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_presets -> {
                startActivity(Intent(this, PresetsActivity::class.java)); true
            }
            R.id.action_log -> {
                startActivity(Intent(this, LogActivity::class.java)); true
            }
            R.id.action_auto_reconnect -> {
                bt.autoReconnect = !bt.autoReconnect
                Toast.makeText(
                    this,
                    if (bt.autoReconnect) R.string.auto_reconnect_on
                    else R.string.auto_reconnect_off,
                    Toast.LENGTH_SHORT
                ).show()
                item.isChecked = bt.autoReconnect
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ------------------------------------------------------------------
    // Адаптер списка устройств
    // ------------------------------------------------------------------

    inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tv_name)
            val tvAddr: TextView = v.findViewById(R.id.tv_addr)
            val tvState: TextView = v.findViewById(R.id.tv_state)
            val tvRssi: TextView = v.findViewById(R.id.tv_rssi)
            val btnBond: Button = v.findViewById(R.id.btn_bond)
            val btnConnect: Button = v.findViewById(R.id.btn_connect)
            val btnDisconnect: Button = v.findViewById(R.id.btn_disconnect)
            val btnForget: Button = v.findViewById(R.id.btn_forget)
            val btnManage: Button = v.findViewById(R.id.btn_manage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = devices.size

        @SuppressLint("MissingPermission")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = devices.values.toList()[position]
            val dev = item.device
            holder.tvName.text = item.name.ifEmpty { getString(R.string.unknown_device) }
            holder.tvAddr.text = dev.address
            holder.tvRssi.text = if (item.isNew && item.rssi != 0) "${item.rssi} дБм" else ""

            val bondState = bt.bondStateOf(dev)
            val isConnectedNow =
                bt.connState == BtManager.ConnState.CONNECTED && bt.connectedDevice?.address == dev.address
            val isConnectingNow =
                bt.connState == BtManager.ConnState.CONNECTING && bt.connectedDevice?.address == dev.address

            holder.tvState.text = when {
                isConnectedNow -> getString(R.string.state_connected)
                isConnectingNow -> getString(R.string.state_connecting)
                bondState == BluetoothDevice.BOND_BONDED -> getString(R.string.state_bonded)
                bondState == BluetoothDevice.BOND_BONDING -> getString(R.string.state_bonding)
                else -> getString(R.string.state_not_bonded)
            }
            holder.tvState.setTextColor(
                when {
                    isConnectedNow -> getColor(R.color.ok_green)
                    bondState == BluetoothDevice.BOND_BONDED -> getColor(R.color.ok_green)
                    else -> getColor(R.color.text_secondary)
                }
            )

            holder.btnBond.visibility = if (bondState != BluetoothDevice.BOND_BONDED) View.VISIBLE else View.GONE
            holder.btnConnect.visibility =
                if (bondState == BluetoothDevice.BOND_BONDED && !isConnectedNow && !isConnectingNow) View.VISIBLE else View.GONE
            holder.btnDisconnect.visibility = if (isConnectedNow || isConnectingNow) View.VISIBLE else View.GONE
            holder.btnForget.visibility = if (bondState == BluetoothDevice.BOND_BONDED) View.VISIBLE else View.GONE
            holder.btnManage.visibility = if (isConnectedNow) View.VISIBLE else View.GONE

            holder.btnBond.setOnClickListener { bondSelected(dev) }
            holder.btnConnect.setOnClickListener { connectToDevice(dev) }
            holder.btnDisconnect.setOnClickListener { bt.disconnect() }
            holder.btnForget.setOnClickListener { forgetDevice(dev) }
            holder.btnManage.setOnClickListener { openDeviceScreen(dev) }
            holder.itemView.setOnClickListener {
                selectedAddress = dev.address
                if (isConnectedNow) openDeviceScreen(dev) else bondSelected(dev)
            }
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    private fun toast(s: CharSequence) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
