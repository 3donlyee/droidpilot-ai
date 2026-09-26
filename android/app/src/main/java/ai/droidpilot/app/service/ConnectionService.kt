package ai.droidpilot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.droidpilot.app.MainActivity
import ai.droidpilot.app.R
import ai.droidpilot.app.core.ApiClient
import ai.droidpilot.app.core.LogSystem
import ai.droidpilot.app.core.SecurePrefs
import ai.droidpilot.app.tools.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the connection alive and runs the
 * command loop: poll → execute → report. Visible by design — the
 * notification reads "DroidPilot AI is connected".
 */
class ConnectionService : Service() {

    companion object {
        const val CHANNEL_ID = "droidpilot_connection"
        const val NOTIF_ID = 1001

        @Volatile
        var running: Boolean = false
            private set
    }

    private lateinit var prefs: SecurePrefs
    private lateinit var api: ApiClient
    private lateinit var executor: ToolExecutor
    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        prefs = SecurePrefs(this)
        api = ApiClient(prefs)
        executor = ToolExecutor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        running = true
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { it.launch { loop() } }
        LogSystem.log("conn", "connection service started")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope?.cancel()
        scope = null
        LogSystem.log("conn", "connection service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun loop() {
        var backoffMs = 1500L
        while (kotlin.coroutines.coroutineContext.isActive && running) {
            if (!api.isConfigured) {
                LogSystem.log("conn", "waiting for configuration (URL + pairing)")
                delay(5000)
                continue
            }
            try {
                val cmd = api.poll(waitSeconds = 20)
                if (cmd != null) {
                    LogSystem.log("conn", "cmd received: ${cmd.tool}")
                    val result = executor.execute(cmd)
                    val posted = api.postResult(cmd, result)
                    LogSystem.log("conn", "result posted: $posted")
                }
                backoffMs = 1500L
            } catch (t: Throwable) {
                LogSystem.log("conn", "connection error: ${t.message?.take(120)}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "DroidPilot Connection", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Shows when DroidPilot AI is connected"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_droid)
            .setContentTitle("DroidPilot AI is connected")
            .setContentText("Agent ready — waiting for commands")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
