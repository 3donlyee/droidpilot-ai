package ai.droidpilot.app.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of DroidPilot events.
 * Exposed to the AI through the get_logs() tool and to the UI.
 * Never store secrets/tokens here.
 */
object LogSystem {
    private const val TAG = "DroidPilot"
    private const val MAX = 500
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${fmt.format(Date())} [$tag] $message"
        Log.i(TAG, "$tag: $message")
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
    }

    @Synchronized
    fun dump(limit: Int = 100): List<String> {
        val all = buf.toList()
        return all.takeLast(limit.coerceIn(1, MAX))
    }

    @Synchronized
    fun clear() = buf.clear()
}
