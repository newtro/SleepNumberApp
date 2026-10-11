package ai.johnnycode.bedcontrol.control

import ai.johnnycode.bedkit.FrameEvent
import ai.johnnycode.bedkit.hex
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of protocol frames (TX/RX with hex) and connection events for the Diagnostics
 * screen. Main thread only.
 */
class DiagnosticsLog(private val maxEntries: Int = 4000) {
    data class Entry(val id: Long, val time: Long, val event: FrameEvent) {
        val timeText: String get() = formatter.get()!!.format(Date(time))
        val line: String get() = "$timeText ${event.direction.label} [${event.channel}] ${event.text}" +
            (event.bytes?.let { " | ${it.hex()}" } ?: "")
    }

    val entries = mutableStateListOf<Entry>()
    private var nextId = 0L

    fun append(event: FrameEvent) {
        entries += Entry(++nextId, System.currentTimeMillis(), event)
        if (entries.size > maxEntries) entries.removeRange(0, entries.size - maxEntries)
    }

    fun info(channel: String, text: String) = append(FrameEvent(FrameEvent.Direction.INFO, channel, text = text))
    fun error(channel: String, text: String) = append(FrameEvent(FrameEvent.Direction.ERROR, channel, text = text))

    fun clear() = entries.clear()

    fun export(header: String): String = (listOf(header, "") + entries.map { it.line }).joinToString("\n")

    companion object {
        private val formatter = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    }
}
