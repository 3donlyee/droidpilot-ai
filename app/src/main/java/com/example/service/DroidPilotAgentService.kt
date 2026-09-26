package com.example.service

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
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.model.CommandRecord
import com.example.data.repository.DroidPilotRepository
import com.example.engine.AdbBridge
import com.example.engine.DeviceManager
import com.example.engine.ToolExecutor
import com.example.network.CloudflareClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DroidPilotAgentService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var pollJob: Job? = null

    private lateinit var repository: DroidPilotRepository
    private lateinit var deviceManager: DeviceManager
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var cloudflareClient: CloudflareClient

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        repository = DroidPilotRepository(db)
        deviceManager = DeviceManager(this, repository)
        val adbBridge = AdbBridge(this)
        toolExecutor = ToolExecutor(this, deviceManager, adbBridge, repository)
        cloudflareClient = CloudflareClient()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("DroidPilot AI is connected"))

        serviceScope.launch {
            deviceManager.initialize()
            val savedUrl = repository.getConfig("worker_url", "")
            if (savedUrl.isNotEmpty()) {
                cloudflareClient.setBaseUrl(savedUrl)
            }
            startPolling()
        }
        _isServiceActive.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopPolling()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            repository.log("INFO", "SERVICE", "Background Agent Service polling started")
            while (isActive) {
                try {
                    val deviceId = deviceManager.getDeviceId()
                    val secret = deviceManager.getDeviceSecret()

                    if (deviceId.isNotEmpty()) {
                        val commands = cloudflareClient.pollCommands(deviceId, secret)
                        for (cmd in commands) {
                            repository.log("ACTION", "SERVICE", "Received command: ${cmd.tool} [${cmd.id}]")
                            repository.recordCommand(
                                CommandRecord(
                                    commandId = cmd.id,
                                    tool = cmd.tool,
                                    argumentsJson = cmd.arguments.toString(),
                                    status = "RUNNING"
                                )
                            )

                            val result = toolExecutor.execute(cmd)
                            repository.updateCommand(
                                CommandRecord(
                                    commandId = cmd.id,
                                    tool = cmd.tool,
                                    argumentsJson = cmd.arguments.toString(),
                                    status = if (result.success) "SUCCESS" else "FAILED",
                                    resultJson = result.data?.toString(),
                                    error = result.error,
                                    completedAt = System.currentTimeMillis()
                                )
                            )

                            cloudflareClient.sendResult(result)
                        }
                    }
                } catch (e: Exception) {
                    repository.log("DEBUG", "SERVICE", "Poll check idle: ${e.message}")
                }
                delay(2000) // Poll interval
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        _isServiceActive.value = false
    }

    override fun onDestroy() {
        stopPolling()
        _isServiceActive.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DroidPilot Agent Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps DroidPilot AI connected to AI Worker"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidPilot AI Agent")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "droidpilot_agent_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.STOP_AGENT"

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, DroidPilotAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DroidPilotAgentService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
