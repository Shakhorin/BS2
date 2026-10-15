package com.gamepoint.control

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Пресет — сохранённый набор параметров (ТЗ 4.4.1).
 * Хранение: SharedPreferences в формате JSON (разрешено разделом 8 ТЗ).
 */
data class Preset(
    val id: String,
    val name: String,
    val type: DeviceType,
    /** WIN_TIME, минут (точка захвата). */
    val winTimeMin: Double,
    /** switchDelay, секунд (точка захвата). */
    val switchDelaySec: Double,
    /** Интервал воскрешения, минут (аптечка). */
    val medkitIntervalMin: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.name)
        put("winTimeMin", winTimeMin)
        put("switchDelaySec", switchDelaySec)
        put("medkitIntervalMin", medkitIntervalMin)
    }

    companion object {
        fun fromJson(o: JSONObject): Preset = Preset(
            id = o.optString("id", java.util.UUID.randomUUID().toString()),
            name = o.optString("name"),
            type = runCatching { DeviceType.valueOf(o.optString("type")) }
                .getOrDefault(DeviceType.CAPTURE_POINT),
            winTimeMin = o.optDouble("winTimeMin", 1.0),
            switchDelaySec = o.optDouble("switchDelaySec", 45.0),
            medkitIntervalMin = o.optInt("medkitIntervalMin", 10)
        )
    }
}

class PresetStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<Preset> {
        val raw = prefs.getString(KEY_PRESETS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Preset.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(preset: Preset) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == preset.id }
        if (idx >= 0) list[idx] = preset else list.add(preset)
        write(list)
    }

    fun delete(id: String) {
        write(all().filterNot { it.id == id })
    }

    private fun write(list: List<Preset>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "gpc_presets"
        private const val KEY_PRESETS = "presets"
    }
}
