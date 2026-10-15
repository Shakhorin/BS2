package com.gamepoint.control

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Экран пресетов (ТЗ 4.4.1, 5.4): список, создание, редактирование,
 * удаление, применение к текущему подключённому устройству.
 */
class PresetsActivity : AppCompatActivity() {

    private lateinit var store: PresetStore
    private lateinit var bt: BtManager
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private val items = mutableListOf<Preset>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_presets)
        setTitle(R.string.presets_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = PresetStore(this)
        bt = BtManager.get(this)
        rv = findViewById(R.id.rv_presets)
        tvEmpty = findViewById(R.id.tv_empty)

        rv.layoutManager = LinearLayoutManager(this)
        rv.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rv.adapter = PresetAdapter()

        findViewById<Button>(R.id.btn_add_preset).setOnClickListener {
            editPreset(null)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun reload() {
        items.clear()
        items.addAll(store.all())
        rv.adapter?.notifyDataSetChanged()
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // Создание / редактирование
    // ------------------------------------------------------------------

    private fun editPreset(existing: Preset?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_preset_edit, null)
        val etName = view.findViewById<EditText>(R.id.et_preset_name)
        val etWin = view.findViewById<EditText>(R.id.et_preset_win_time)
        val etDelay = view.findViewById<EditText>(R.id.et_preset_delay)
        val etInterval = view.findViewById<EditText>(R.id.et_preset_interval)
        val rgType = view.findViewById<RadioGroup>(R.id.rg_preset_type)

        var type = existing?.type ?: DeviceType.CAPTURE_POINT
        etName.setText(existing?.name ?: "")
        etWin.setText(BtManager.formatFloat(existing?.winTimeMin ?: 1.5))
        etDelay.setText(BtManager.formatFloat(existing?.switchDelaySec ?: 45.0))
        etInterval.setText((existing?.medkitIntervalMin ?: 10).toString())
        rgType.check(if (type == DeviceType.MEDKIT) R.id.rb_preset_medkit else R.id.rb_preset_capture)

        fun updateFields() {
            etWin.isEnabled = type == DeviceType.CAPTURE_POINT
            etDelay.isEnabled = type == DeviceType.CAPTURE_POINT
            etInterval.isEnabled = type == DeviceType.MEDKIT
        }
        updateFields()
        rgType.setOnCheckedChangeListener { _, id ->
            type = if (id == R.id.rb_preset_medkit) DeviceType.MEDKIT else DeviceType.CAPTURE_POINT
            updateFields()
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.preset_create else R.string.preset_edit)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.err_preset_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val win = etWin.text.toString().replace(',', '.').toDoubleOrNull() ?: 1.5
                val delay = etDelay.text.toString().replace(',', '.').toDoubleOrNull() ?: 45.0
                val interval = etInterval.text.toString().toIntOrNull() ?: 10
                if (!BtManager.validateWinTime(win)) {
                    Toast.makeText(this, R.string.err_win_time, Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                if (!BtManager.validateSwitchDelay(delay)) {
                    Toast.makeText(this, R.string.err_delay, Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                if (!BtManager.validateMedkitInterval(interval)) {
                    Toast.makeText(this, R.string.err_interval, Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                val preset = Preset(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name,
                    type = type,
                    winTimeMin = win,
                    switchDelaySec = delay,
                    medkitIntervalMin = interval
                )
                store.save(preset)
                CommandLog.get(this).info("Пресет сохранён: $name")
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deletePreset(preset: Preset) {
        AlertDialog.Builder(this)
            .setTitle(R.string.preset_delete)
            .setMessage(getString(R.string.preset_delete_message, preset.name))
            .setPositiveButton(R.string.ok) { _, _ ->
                store.delete(preset.id)
                CommandLog.get(this).info("Пресет удалён: ${preset.name}")
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Применение пресета к подключённому устройству
    // ------------------------------------------------------------------

    private fun applyPreset(preset: Preset) {
        if (bt.connState != BtManager.ConnState.CONNECTED) {
            Toast.makeText(this, R.string.err_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        CommandLog.get(this).info("Применение пресета «${preset.name}»")
        setBusy(true)
        Thread {
            val results = mutableListOf<Pair<String, String?>>()
            when (preset.type) {
                DeviceType.CAPTURE_POINT -> {
                    results += "T" to bt.sendWinTime(preset.winTimeMin)
                    results += "D" to bt.sendSwitchDelay(preset.switchDelaySec)
                }
                DeviceType.MEDKIT -> {
                    results += "I" to bt.sendMedkitInterval(preset.medkitIntervalMin)
                }
            }
            runOnUiThread {
                setBusy(false)
                val failed = results.filter { it.second == null }
                if (failed.isEmpty()) {
                    Toast.makeText(this, getString(R.string.preset_applied, preset.name), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.preset_apply_partial, preset.name),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        rv.isClickable = !busy
    }

    // ------------------------------------------------------------------

    inner class PresetAdapter : RecyclerView.Adapter<PresetAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tv_name)
            val tvParams: TextView = v.findViewById(R.id.tv_params)
            val btnApply: Button = v.findViewById(R.id.btn_apply_preset)
            val btnEdit: Button = v.findViewById(R.id.btn_edit_preset)
            val btnDelete: Button = v.findViewById(R.id.btn_delete_preset)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_preset, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            holder.tvName.text = p.name
            holder.tvParams.text = when (p.type) {
                DeviceType.CAPTURE_POINT -> getString(
                    R.string.type_capture
                ) + ": T=" + BtManager.formatFloat(p.winTimeMin) + " мин, D=" +
                        BtManager.formatFloat(p.switchDelaySec) + " с"
                DeviceType.MEDKIT -> getString(R.string.type_medkit) +
                        ": I=" + p.medkitIntervalMin + " мин"
            }
            holder.btnApply.setOnClickListener { applyPreset(p) }
            holder.btnEdit.setOnClickListener { editPreset(p) }
            holder.btnDelete.setOnClickListener { deletePreset(p) }
        }
    }
}
