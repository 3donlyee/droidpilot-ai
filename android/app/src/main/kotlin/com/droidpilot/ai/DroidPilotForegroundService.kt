package com.droidpilot.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.droidpilot.ai.bridge.CommandEnvelope
import com.droidpilot.ai.bridge.ResultEnvelope
import com.droidpilot.ai.bridge.WorkerClient
import com.droidpilot.ai.tools.ToolExecutor
import com.droidpilot.ai.tools.ToolRegistry
import com.droidpilot.ai.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * DroidPilot AI — Foreground Service.
 *
 * Holds the long-lived WebSocket connection to the Cloudflare Worker,
 * receives [CommandEnvelope]s, dispatches them to [ToolExecutor], and
 * pushes [ResultEnvelope]s back over the socket.
 *
 * Lifecycle:
 *  - [onCreate]       : create notification channel, become foreground.
 *  - [onStartCommand] : read `workerUrl`, `deviceId`, `deviceSecret` from
 *                       the intent extras (or Prefs), then connect via
 *                       [WorkerClient.connectCommandChannel].
 *  - [onDestroy]      : cancel coroutine scope, close socket.
 *
 * Start intent extras:
 *   EXTRA_WORKER_URL : String (required)
 *   EXTRA_DEVICE_ID   : String (required)
 *   EXTRA_DEVICE_SECRET: String (required)
 */
class DroidPilotForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandJob: Job? = null
    private var workerClient: WorkerClient? = null

    // ----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Logger.i(TAG, "onCreate")
        createNotificationChannel()
        // Pre-warm tool registry
        ToolRegistry.ensureInitialised()
        // Become foreground immediately — Android 8+ requires this within 5s.
        startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_text_connecting))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val workerUrl = intent?.getStringExtra(EXTRA_WORKER_URL)
            ?: Prefs.get().workerUrl
        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
            ?: Prefs.get().deviceId
        val deviceSecret = intent?.getStringExtra(EXTRA_DEVICE_SECRET)
            ?: Prefs.get().deviceSecret

        if (workerUrl.isBlank()) {
            Logger.e(TAG, "Worker URL is blank — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        if (deviceId.isBlank() || deviceSecret.isBlank()) {
            Logger.e(TAG, "Device not paired — stopping service")
            updateNotification(R.string.notification_text_disconnected)
            stopSelf()
            return START_NOT_STICKY
        }

        connect(workerUrl, deviceId, deviceSecret)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Logger.i(TAG, "onDestroy")
        commandJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------

    private fun connect(workerUrl: String, deviceId: String, deviceSecret: String) {
        commandJob?.cancel()
        workerClient = WorkerClient()
        updateNotification(R.string.notification_text_connecting)

        commandJob = scope.launch {
            val handle = workerClient!!.connectCommandChannel(
                workerUrl = workerUrl,
                deviceId = deviceId,
                deviceSecret = deviceSecret,
                onCommand = { env -> onCommand(env) },
                onStateChange = { state -> onChannelState(state) }
            )
            // Hold the job alive; cancellation cancels the socket via the handle.
            // We register the handle so onDestroy can cancel it cleanly.
            activeHandle = handle
        }
    }

    private fun onCommand(env: CommandEnvelope) {
        Logger.i(TAG, "← command id=${env.id} tool=${env.tool}")
        scope.launch {
            val result: ResultEnvelope = ToolExecutor.execute(env)
            workerClient?.sendResult(result)
            Logger.i(TAG, "→ result id=${result.id} success=${result.success}")
        }
    }

    private fun onChannelState(state: WorkerClient.ChannelState) {
        when (state) {
            WorkerClient.ChannelState.CONNECTED ->
                updateNotification(R.string.notification_text_connected)
            WorkerClient.ChannelState.CONNECTING ->
                updateNotification(R.string.notification_text_connecting)
            WorkerClient.ChannelState.DISCONNECTED,
            WorkerClient.ChannelState.CLOSING ->
                updateNotification(R.string.notification_text_disconnected)
            is WorkerClient.ChannelState.FAILED ->
                updateNotification(R.string.notification_text_disconnected)
        }
    }

    // ----------------------------------------------------------------------
    // Notification
    // ----------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.notification_channel_id),
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(textRes: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(textRes))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(textRes: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(textRes))
    }

    // ----------------------------------------------------------------------

    @Volatile
    private var activeHandle: WorkerClient.CancellableHandle? = null

    companion object {
        private const val TAG = "FgService"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_WORKER_URL = "worker_url"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_SECRET = "device_secret"

        fun start(context: Context, workerUrl: String, deviceId: String, deviceSecret: String) {
            val intent = Intent(context, DroidPilotForegroundService::class.java).apply {
                putExtra(EXTRA_WORKER_URL, workerUrl)
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_DEVICE_SECRET, deviceSecret)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DroidPilotForegroundService::class.java))
        }
    }
}
