package com.droidpilot.ai.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * DroidPilot AI — lightweight in-app logger.
 *
 * - Keeps the last [MAX_LINES] lines in memory (FIFO ring buffer)
 * - Writes everything to logcat as well (`Log.d/i/w/e`)
 * - Exposes a [LiveData] (`logsLive`) that [MainActivity] observes to refresh the on-screen log
 * - Thread-safe (uses an explicit lock around the ring buffer)
 *
 * Levels: DEBUG, INFO, WARN, ERROR.
 */
object Logger {

    private const val TAG = "DroidPilot"
    private const val MAX_LINES = 500

    enum class Level(val tag: String) {
        DEBUG("DEBUG"),
        INFO(" INFO"),
        WARN(" WARN"),
        ERROR("ERROR")
    }

    private data class LogLine(
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        override fun toString(): String = "$timestamp  ${level.tag}  $tag: $message"
    }

    private val lock = Any()
    private val buffer = ArrayDeque<LogLine>(MAX_LINES)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * LiveData carrying the *current* rendered log text (joined by newlines).
     * Updated on the main thread so [android.widget.TextView] observers can bind directly.
     */
    private val _logsLive = MutableLiveData<String>("")
    val logsLive: LiveData<String> = _logsLive

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String = TAG, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String = TAG, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String = TAG, message: String, t: Throwable? = null) =
        log(Level.WARN, tag, if (t != null) "$message — ${t.javaClass.simpleName}: ${t.message}" else message)
    fun e(tag: String = TAG, message: String, t: Throwable? = null) =
        log(Level.ERROR, tag, if (t != null) "$message — ${t.javaClass.simpleName}: ${t.message}" else message)

    fun log(level: Level, tag: String, message: String) {
        val line = LogLine(timeFormat.format(Date()), level, tag, message)
        synchronized(lock) {
            if (buffer.size >= MAX_LINES) {
                buffer.pollFirst()
            }
            buffer.addLast(line)
        }
        // Mirror to logcat
        when (level) {
            Level.DEBUG -> Log.d(tag, message)
            Level.INFO -> Log.i(tag, message)
            Level.WARN -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message)
        }
        // Republish on main thread for UI consumers
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { publishSnapshot() }
        } else {
            publishSnapshot()
        }
    }

    private fun publishSnapshot() {
        val snapshot = synchronized(lock) { buffer.joinToString("\n") }
        _logsLive.postValue(snapshot)
    }

    /** Returns an immutable snapshot copy of the log buffer (newest last). */
    fun getLogs(): List<String> = synchronized(lock) {
        buffer.map { it.toString() }
    }

    /** Clear the log buffer. */
    fun clear() {
        synchronized(lock) { buffer.clear() }
        handler.post { publishSnapshot() }
    }
}
