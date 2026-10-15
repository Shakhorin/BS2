package com.gamepoint.control

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Журнал обмена командами (ТЗ 4.4.2, 7).
 * Хранит записи во внутренней памяти приложения и в памяти процесса,
 * уведомляет подписчиков об изменениях. Все ошибки дублируются в журнал.
 */
class CommandLog private constructor(context: Context) {

    enum class Direction { TX, RX, INFO, ERROR }

    data class Entry(
        val time: Long,
        val direction: Direction,
        val text: String
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val entries = CopyOnWriteArrayList<Entry>()
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        // Восстанавливаем последние записи из хранилища.
        val saved = prefs.getString(KEY_LOG, "") ?: ""
        if (saved.isNotEmpty()) {
            saved.split("\n").forEach { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size == 3) {
                    val dir = parts[1].toIntOrNull() ?: Direction.INFO.ordinal
                    entries.add(Entry(parts[0].toLong(), Direction.values()[dir], parts[2]))
                }
            }
        }
    }

    fun add(direction: Direction, text: String) {
        val e = Entry(System.currentTimeMillis(), direction, text)
        entries.add(e)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        persist()
        notifyListeners()
    }

    fun info(text: String) = add(Direction.INFO, text)
    fun error(text: String) = add(Direction.ERROR, text)
    fun tx(text: String) = add(Direction.TX, text)
    fun rx(text: String) = add(Direction.RX, text)

    fun snapshot(): List<Entry> = entries.toList()

    fun clear() {
        entries.clear()
        persist()
        notifyListeners()
    }

    /** Лог в виде текста для копирования (ТЗ 5.5). */
    fun asText(): String = entries.joinToString("\n") { e ->
        "${timeFormat.format(Date(e.time))} ${tag(e.direction)} ${e.text}"
    }

    private fun tag(d: Direction): String = when (d) {
        Direction.TX -> ">>"
        Direction.RX -> "<<"
        Direction.INFO -> "--"
        Direction.ERROR -> "!!"
    }

    fun addListener(r: Runnable) = listeners.add(r)
    fun removeListener(r: Runnable) = listeners.remove(r)

    private fun notifyListeners() = listeners.forEach { it.run() }

    private fun persist() {
        val sb = StringBuilder()
        entries.forEach { e ->
            sb.append(e.time).append('|').append(e.direction.ordinal).append('|')
                .append(e.text.replace("\n", "\\n")).append('\n')
        }
        prefs.edit().putString(KEY_LOG, sb.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "gpc_log"
        private const val KEY_LOG = "log"
        private const val MAX_ENTRIES = 500

        @Volatile
        private var instance: CommandLog? = null

        fun get(context: Context): CommandLog =
            instance ?: synchronized(this) {
                instance ?: CommandLog(context.applicationContext).also { instance = it }
            }
    }
}
