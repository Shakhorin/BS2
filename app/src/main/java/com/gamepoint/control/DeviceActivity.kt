package com.gamepoint.control

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Экран устройства (ТЗ 5.3): параметры в зависимости от типа устройства,
 * валидация с подсветкой ошибок, кнопки «Применить» / «Сброс»,
 * область ответов устройства.
 */
class DeviceActivity : AppCompatActivity(), BtManager.Listener {

    companion object {
        const val EXTRA_ADDRESS = "device_address"
        private const val KEY_TYPE = "device_type"
        private const val KEY_PIN = "pin_hint_shown"
    }

    private lateinit var bt: BtManager
    private lateinit var log: CommandLog
    private var device: BluetoothDevice? = null

    private lateinit var typeContainer: LinearLayout
    private lateinit var captureGroup: LinearLayout
    private lateinit var medkitGroup: LinearLayout
    private lateinit var tilWinTime: TextInputLayout
    private lateinit var etWinTime: TextInputEditText
    private lateinit var tilDelay: TextInputLayout
    private lateinit var etDelay: TextInputEditText
    private lateinit var tilInterval: TextInputLayout
    private lateinit var etInterval: TextInputEditText
    private lateinit var tvResponses: TextView
    private lateinit var tvConnStatus: TextView
    private lateinit var btnApply: Button
    private lateinit var btnReset: Button
    private lateinit var spinType: android.widget.RadioGroup

    private var deviceType = DeviceType.CAPTURE_POINT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device)

        bt = BtManager.get(this)
        log = CommandLog.get(this)

        typeContainer = findViewById(R.id.type_container)
        captureGroup = findViewById(R.id.group_capture)
        medkitGroup = findViewById(R.id.group_medkit)
        tilWinTime = findViewById(R.id.til_win_time)
        etWinTime = findViewById(R.id.et_win_time)
        tilDelay = findViewById(R.id.til_delay)
        etDelay = findViewById(R.id.et_delay)
        tilInterval = findViewById(R.id.til_interval)
        etInterval = findViewById(R.id.et_interval)
        tvResponses = findViewById(R.id.tv_responses)
        tvConnStatus = findViewById(R.id.tv_conn_status)
        btnApply = findViewById(R.id.btn_apply)
        btnReset = findViewById(R.id.btn_reset)
        spinType = findViewById(R.id.rg_type)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.device_title)

        val addr = intent.getStringExtra(EXTRA_ADDRESS)
        device = addr?.let { bt.deviceFromAddress(it) } ?: bt.connectedDevice

        deviceType = savedInstanceState?.let {
            runCatching { DeviceType.valueOf(it.getString(KEY_TYPE)!!) }
                .getOrDefault(DeviceType.CAPTURE_POINT)
        } ?: guessType()

        applyTypeToUi(showPinHint = savedInstanceState?.getBoolean(KEY_PIN) != true)

        // Подстановка текущих значений из сохранённого пресета/параметров.
        if (etWinTime.text.isNullOrEmpty()) etWinTime.setText("1.5")
        if (etDelay.text.isNullOrEmpty()) etDelay.setText("45")
        if (etInterval.text.isNullOrEmpty()) etInterval.setText("10")

        spinType.setOnCheckedChangeListener { _, checkedId ->
            deviceType = if (checkedId == R.id.rb_medkit) DeviceType.MEDKIT
            else DeviceType.CAPTURE_POINT
            saveTypeForDevice()
            applyTypeToUi(showPinHint = false)
            updateValidation()
        }

        etWinTime.addTextChangedListenerAfter { updateValidation() }
        etDelay.addTextChangedListenerAfter { updateValidation() }
        etInterval.addTextChangedListenerAfter { updateValidation() }

        btnApply.setOnClickListener { sendParameters() }
        btnReset.setOnClickListener { sendReset() }

        updateConnectionUi()
        updateValidation()
    }

    override fun onStart() {
        super.onStart()
        bt.addListener(this)
    }

    override fun onStop() {
        bt.removeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TYPE, deviceType.name)
        outState.putBoolean(KEY_PIN, true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish(); return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------
    // Тип устройства
    // ------------------------------------------------------------------

    private fun prefsKey() = "type_" + (device?.address ?: "default")

    private fun guessType(): DeviceType {
        val dev = device
        // Сохранённый ранее тип имеет приоритет.
        val saved = getSharedPreferences("gpc_bt", MODE_PRIVATE).getString(prefsKey(), null)
        if (saved != null) runCatching { return DeviceType.valueOf(saved) }
        // Автоопределение по имени (ТЗ 4.4.3 — эвристика; прошивка может ответить на V).
        val name = dev?.let { bt.safeName(it) } ?: ""
        return DeviceType.fromName(name)
    }

    private fun saveTypeForDevice() {
        getSharedPreferences("gpc_bt", MODE_PRIVATE).edit()
            .putString(prefsKey(), deviceType.name).apply()
    }

    private fun applyTypeToUi(showPinHint: Boolean) {
        title = getString(R.string.device_title) + " — " +
                getString(if (deviceType == DeviceType.MEDKIT) R.string.type_medkit else R.string.type_capture)
        captureGroup.visibility = if (deviceType == DeviceType.CAPTURE_POINT) android.view.View.VISIBLE else android.view.View.GONE
        medkitGroup.visibility = if (deviceType == DeviceType.MEDKIT) android.view.View.VISIBLE else android.view.View.GONE
        spinType.check(
            if (deviceType == DeviceType.MEDKIT) R.id.rb_medkit else R.id.rb_capture
        )
        if (showPinHint && deviceType == DeviceType.MEDKIT) {
            // ТЗ 4.3: звук и режим аптечки всегда включены, доп. вопросов нет.
            Toast.makeText(this, R.string.medkit_note, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // Валидация (ТЗ 4.2, 4.3, 7)
    // ------------------------------------------------------------------

    private fun parseWinTime(): Double? =
        etWinTime.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

    private fun parseDelay(): Double? =
        etDelay.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

    private fun parseInterval(): Int? =
        etInterval.text?.toString()?.trim()?.toIntOrNull()

    private fun updateValidation() {
        var ok = true
        if (deviceType == DeviceType.CAPTURE_POINT) {
            val t = parseWinTime()
            val tOk = t != null && BtManager.validateWinTime(t)
            tilWinTime.error = if (!tOk) getString(R.string.err_win_time) else null
            ok = ok && tOk

            val d = parseDelay()
            val dOk = d != null && BtManager.validateSwitchDelay(d)
            tilDelay.error = if (!dOk) getString(R.string.err_delay) else null
            ok = ok && dOk
        } else {
            val i = parseInterval()
            val iOk = i != null && BtManager.validateMedkitInterval(i)
            tilInterval.error = if (!iOk) getString(R.string.err_interval) else null
            ok = ok && iOk
        }
        // Блокировка отправки при некорректном вводе или отсутствии соединения.
        btnApply.isEnabled = ok && bt.connState == BtManager.ConnState.CONNECTED
        btnReset.isEnabled = bt.connState == BtManager.ConnState.CONNECTED
    }

    // ------------------------------------------------------------------
    // Отправка команд (раздел 6 ТЗ) — в фоновом потоке, ответ ~3 с
    // ------------------------------------------------------------------

    private fun sendParameters() {
        if (bt.connState != BtManager.ConnState.CONNECTED) {
            showError(getString(R.string.err_not_connected))
            return
        }
        updateValidation()
        if (!btnApply.isEnabled) return

        setBusy(true)
        appendResponse(getString(R.string.sending))
        Thread {
            val results = mutableListOf<Pair<String, String?>>()
            if (deviceType == DeviceType.CAPTURE_POINT) {
                parseWinTime()?.let { t ->
                    results += "T${BtManager.formatFloat(t)}" to bt.sendWinTime(t)
                }
                parseDelay()?.let { d ->
                    results += "D${BtManager.formatFloat(d)}" to bt.sendSwitchDelay(d)
                }
            } else {
                parseInterval()?.let { i ->
                    results += "I$i" to bt.sendMedkitInterval(i)
                }
            }
            runOnUiThread {
                setBusy(false)
                results.forEach { (cmd, resp) ->
                    if (resp != null) {
                        appendResponse("$cmd → $resp")
                    } else {
                        showError(getString(R.string.err_no_response, cmd))
                    }
                }
                updateValidation()
            }
        }.start()
    }

    private fun sendReset() {
        if (bt.connState != BtManager.ConnState.CONNECTED) {
            showError(getString(R.string.err_not_connected))
            return
        }
        setBusy(true)
        Thread {
            val resp = bt.sendReset()
            runOnUiThread {
                setBusy(false)
                if (resp != null) appendResponse("R → $resp")
                else showError(getString(R.string.err_no_response, "R"))
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        btnApply.isEnabled = !busy && btnApply.isContentValid()
        btnReset.isEnabled = !busy && bt.connState == BtManager.ConnState.CONNECTED
        if (busy) appendResponse("…")
    }

    private fun Boolean.isContentValid(): Boolean = this

    // ------------------------------------------------------------------
    // UI состояния подключения и ответы
    // ------------------------------------------------------------------

    private fun updateConnectionUi() {
        val dev = device
        val connectedToThis = bt.connState == BtManager.ConnState.CONNECTED &&
                dev != null && bt.connectedDevice?.address == dev.address
        tvConnStatus.text = when {
            connectedToThis -> getString(R.string.status_connected_short, dev?.let { bt.safeAlias(it) }.orEmpty())
            bt.connState == BtManager.ConnState.CONNECTING -> getString(R.string.status_connecting)
            else -> getString(R.string.status_disconnected_short)
        }
        tvConnStatus.setTextColor(
            getColor(if (connectedToThis) R.color.ok_green else R.color.error_red)
        )
        updateValidation()
    }

    private fun appendResponse(text: String) {
        tvResponses.append("\n$text")
        (tvResponses.parent as? android.widget.ScrollView)?.post {
            (tvResponses.parent as? android.widget.ScrollView)?.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }

    private fun showError(msg: String) {
        appendResponse("ОШИБКА: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------
    // BtManager.Listener
    // ------------------------------------------------------------------

    override fun onConnectionStateChanged(state: BtManager.ConnState, device: BluetoothDevice?) {
        updateConnectionUi()
    }

    override fun onDataReceived(line: String) {
        appendResponse("≪ $line")
    }

    @SuppressLint("MissingPermission")
    override fun onError(message: String) {
        showError(message)
        // Потеря соединения — уведомление и возврат на главный экран (ТЗ 7).
        if (bt.connState == BtManager.ConnState.DISCONNECTED) {
            Toast.makeText(this, R.string.connection_lost_return, Toast.LENGTH_LONG).show()
            val i = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(i)
            finish()
        }
    }

    // Небольшая надстройка: TextWatcher после изменения текста.
    private fun EditText.addTextChangedListenerAfter(action: () -> Unit) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = action()
        })
    }
}
