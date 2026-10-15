package com.gamepoint.control

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран журнала обмена (ТЗ 4.4.2, 5.5): записи с временем и направлением,
 * очистка и копирование.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var log: CommandLog
    private lateinit var rv: RecyclerView
    private lateinit var adapter: LogAdapter
    private val entries = mutableListOf<CommandLog.Entry>()
    private val refreshRunnable = Runnable {
        runOnUiThread {
            entries.clear()
            entries.addAll(log.snapshot())
            adapter.notifyDataSetChanged()
            if (entries.isNotEmpty()) {
                rv.scrollToPosition(entries.size - 1)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        setTitle(R.string.log_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        log = CommandLog.get(this)
        rv = findViewById(R.id.rv_log)
        rv.layoutManager = LinearLayoutManager(this)
        rv.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        adapter = LogAdapter()
        rv.adapter = adapter

        findViewById<Button>(R.id.btn_clear_log).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.btn_copy_log).setOnClickListener { copyLog() }
    }

    override fun onStart() {
        super.onStart()
        log.addListener(refreshRunnable)
        refreshRunnable.run()
    }

    override fun onStop() {
        log.removeListener(refreshRunnable)
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.log_clear)
            .setMessage(R.string.log_clear_message)
            .setPositiveButton(R.string.ok) { _, _ -> log.clear() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copyLog() {
        val text = log.asText()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("GamePoint log", text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tv_entry)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = entries[position]
            val arrow = when (e.direction) {
                CommandLog.Direction.TX -> "→"   // отправлено
                CommandLog.Direction.RX -> "←"   // получено
                CommandLog.Direction.INFO -> "ℹ"
                CommandLog.Direction.ERROR -> "⚠"
            }
            holder.tv.text = "${fmt.format(Date(e.time))}  $arrow  ${e.text}"
            holder.tv.setTextColor(
                when (e.direction) {
                    CommandLog.Direction.ERROR -> getColor(R.color.error_red)
                    CommandLog.Direction.RX -> getColor(R.color.rx_blue)
                    CommandLog.Direction.TX -> getColor(R.color.ok_green)
                    else -> getColor(R.color.text_secondary)
                }
            )
        }
    }
}
