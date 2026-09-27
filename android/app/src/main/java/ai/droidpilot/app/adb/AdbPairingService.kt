package ai.droidpilot.app.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import ai.droidpilot.app.MainActivity
import ai.droidpilot.app.core.LogSystem
import ai.droidpilot.app.core.SecurePrefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * aMiNo — Wireless ADB pairing service (no root, no Shizuku, no Termux).
 *
 * UX contract:
 *   1. The user taps «بدء اقتران ADB» in the app → this FGS starts and posts a
 *      persistent interactive notification with a pairing-code input field.
 *   2. The user stays on Settings → Developer options → Wireless debugging and
 *      opens "Pair device with pairing code" (6-digit code + IP:port appear).
 *   3. The pairing port is discovered automatically via mDNS
 *      (_adb-tls-pairing._tcp) and, as a fallback, by reading the dialog
 *      through the app's accessibility service. The user never leaves Settings.
 *   4. The code is typed directly into the notification (RemoteInput) and the
 *      REAL ADB pairing protocol runs (SPAKE2 + TLS, see AdbPairingClient).
 *   5. On success: «ADB مقترن بنجاح» → connection phase on the connection port
 *      (_adb-tls-connect._tcp) → «ADB متصل» → state saved for AdbBridge.
 *
 * Security note: Android offers no public API for a third-party app to read
 * the wireless-debugging ports — mDNS (public NSD API) + accessibility
 * (public API) are the official mechanisms Shizuku itself uses.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {
        const val TAG = "AdbPairing"
        const val CHANNEL_ID = "adb_pairing"
        const val NOTIF_ID = 4001

        const val ACTION_START = "ai.droidpilot.app.adb.START"
        const val ACTION_CODE = "ai.droidpilot.app.adb.CODE"
        const val ACTION_ENDPOINT = "ai.droidpilot.app.adb.ENDPOINT"
        const val ACTION_CANCEL = "ai.droidpilot.app.adb.CANCEL"

        const val EXTRA_ENDPOINT = "endpoint" // "ip:port" from the accessibility watcher

        const val KEY_CODE = "pairing_code"
        const val KEY_MANUAL_ENDPOINT = "manual_endpoint"

        const val BROADCAST_STATE = "ai.droidpilot.app.ADB_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"

        const val STATE_WAITING = "waiting"
        const val STATE_PAIRING = "pairing"
        const val STATE_PAIRED = "paired"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_FAILED = "failed"

        private const val TIMEOUT_MS = 5 * 60 * 1000L

        /** Hook used by DroidPilotAccessibilityService to hand us the dialog's "IP:port". */
        @Volatile
        var endpointListener: ((String) -> Unit)? = null
    }

    private lateinit var prefs: SecurePrefs
    private lateinit var notifManager: NotificationManager
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var mdnsPairing: AdbMdns? = null
    private var mdnsConnect: AdbMdns? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var pairingPort: Int = -1
    @Volatile private var manualEndpoint: String? = null
    @Volatile private var pendingCode: String? = null
    @Volatile private var phase: String = STATE_WAITING
    @Volatile private var busy = false

    private val timeoutRunnable = Runnable {
        if (phase != STATE_CONNECTED && phase != STATE_PAIRED) {
            notifyState(STATE_FAILED, "انتهت مهلة الاقتران. أعد المحاولة.")
            stopEverything()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = SecurePrefs(this)
        notifManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "اقتران ADB", NotificationManager.IMPORTANCE_HIGH
        )
        channel.setDescription("اقتران Wireless ADB الحقيقي داخل aMiNo")
        notifManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification(STATE_WAITING, null))
                notifyState(STATE_WAITING, "بانتظار رمز الاقتران…")
                startPairingDiscovery()
                handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                LogSystem.log("adb", "pairing service started")
            }
            ACTION_CODE -> {
                val fromInput = RemoteInput.getResultsFromIntent(intent)
                var code = fromInput?.getCharSequence(KEY_CODE)?.toString()?.trim() ?: ""
                code = code.filter { it.isDigit() }
                var manual = fromInput?.getCharSequence(KEY_MANUAL_ENDPOINT)?.toString()?.trim()
                if (manual.isNullOrBlank()) manual = null
                // FIX: after a successful PAIRING, a manual entry is a CONNECT
                // endpoint (ColorOS mDNS is often blocked) — verify it directly.
                if ((phase == STATE_PAIRED || phase == STATE_CONNECTING) && manual != null) {
                    val port = manual.substringAfterLast(':').toIntOrNull()
                    if (port != null && port in 1024..65535) {
                        verifyConnection(port)
                    } else {
                        updateNotification(STATE_PAIRED, "الصيغة يجب أن تكون IP:منفذ مثل 192.168.1.7:39045")
                    }
                    return START_NOT_STICKY
                }
                if (code.length >= 6) {
                    pendingCode = code.take(6)
                    if (manual != null) manualEndpoint = manual
                    tryPairIfReady()
                } else {
                    updateNotification(STATE_WAITING, "الرمز يجب أن يكون 6 أرقام — أعد الإدخال")
                }
            }
            ACTION_ENDPOINT -> {
                val ep = intent.getStringExtra(EXTRA_ENDPOINT)
                if (!ep.isNullOrBlank()) handleEndpoint(ep)
            }
            ACTION_CANCEL -> {
                stopEverything()
            }
            else -> {
                // Null-action delivery (e.g. restart): show the notification to satisfy FGS contract.
                startForeground(NOTIF_ID, buildNotification(STATE_WAITING, null))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------- discovery

    private fun acquireMulticast() {
        if (multicastLock != null) return
        val wifi = application.getSystemService(WifiManager::class.java)
        if (wifi != null) {
            val lock = wifi.createMulticastLock("amino_adb_pairing")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        }
    }

    private fun releaseMulticast() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun startPairingDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        acquireMulticast()
        endpointListener = { ep ->
            handler.post {
                handleEndpoint(ep)
            }
        }
        // Arm the (cheap) accessibility watcher as fallback for the port.
        try {
            ai.droidpilot.app.access.DroidPilotAccessibilityService.instance?.setAdbPairWatching(true)
        } catch (_: Throwable) {}
        val mdns = AdbMdns(this, AdbMdns.TLS_PAIRING) { port ->
            handler.post {
                if (port > 0) {
                    pairingPort = port
                    LogSystem.log("adb", "mDNS: pairing port $port")
                    tryPairIfReady()
                }
            }
        }
        mdnsPairing = mdns
        mdns.start()
    }

    /**
     * FIX: one router for every discovered/typed endpoint.
     *  - WAITING phase  → it is the PAIRING endpoint → try to pair.
     *  - PAIRED phase   → it is the CONNECT endpoint (the Wireless debugging
     *    main dialog shows IP:port of the connect service) → verify directly.
     *  Without this, after pairing on ColorOS (mDNS blocked) the app waited
     *  forever on «جارٍ البحث عن منفذ الاتصال…».
     */
    private fun handleEndpoint(ep: String) {
        val idx = ep.lastIndexOf(':')
        if (idx <= 0) return
        val port = ep.substring(idx + 1).toIntOrNull() ?: return
        LogSystem.log("adb", "endpoint in phase=$phase: $ep")
        when (phase) {
            STATE_PAIRED, STATE_CONNECTING -> {
                if (port in 1024..65535) verifyConnection(port)
            }
            STATE_WAITING -> {
                if (pairingPort <= 0 && manualEndpoint == null) {
                    manualEndpoint = ep
                    tryPairIfReady()
                }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------- pairing

    private fun tryPairIfReady() {
        val code = pendingCode ?: return
        if (busy) return
        val endpoint = manualEndpoint ?: if (pairingPort > 0) "127.0.0.1:$pairingPort" else null
        if (endpoint == null) {
            updateNotification(
                STATE_WAITING,
                "استلمت الرمز — في انتظار منفذ الاقتران: افتح «اقتران الجهاز برمز» في إعدادات لاسلكي"
            )
            return
        }
        busy = true
        phase = STATE_PAIRING
        updateNotification(STATE_PAIRING, "جارٍ الاقتران عبر $endpoint …")
        notifyState(STATE_PAIRING, "جارٍ تنفيذ بروتوكول الاقتران…")

        executor.execute {
            var client: AdbPairingClient? = null
            try {
                val key = AdbKeyManager.load(prefs)
                val idx = endpoint.lastIndexOf(':')
                if (idx <= 0) throw IllegalArgumentException("endpoint غير صالح: $endpoint")
                val host = endpoint.substring(0, idx)
                val port = endpoint.substring(idx + 1).toInt()
                client = AdbPairingClient(host, port, code, key)
                val ok = client.start()
                if (ok) {
                    prefs.adbPaired = true
                    prefs.adbEnabled = true
                    prefs.adbLastPairPort = port
                    phase = STATE_PAIRED
                    notifyState(STATE_PAIRED, "ADB مقترن بنجاح")
                    updateNotification(
                        STATE_PAIRED,
                        "ADB مقترن بنجاح — أبقِ شاشة «التصحيح اللاسلكي» مفتوحة للعثور على منفذ الاتصال (أو أدخله يدويًا من الزر)"
                    )
                    handler.removeCallbacks(timeoutRunnable)
                    startConnectionDiscovery()
                } else {
                    fail("فشل الاقتران — تأكد من الرمز والمنفذ ثم أعد المحاولة")
                }
            } catch (e: AdbInvalidPairingCodeException) {
                fail("الرمز غير صحيح — افتح نافذة الاقتران من جديد وأدخل الرمز الجديد")
            } catch (t: Throwable) {
                LogSystem.log("adb", "pairing error: ${t.message}")
                fail("فشل الاقتران: ${t.message?.take(120)}")
            } finally {
                try { client?.close() } catch (_: Exception) {}
                busy = false
            }
        }
    }

    private fun fail(message: String) {
        phase = STATE_FAILED
        notifyState(STATE_FAILED, message)
        // back to waiting with the code input still available
        pendingCode = null
        updateNotification(STATE_WAITING, "$message\nأعد إدخال الرمز من الإشعار.")
    }

    // ----------------------------------------------------------- connection

    private fun startConnectionDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try { mdnsPairing?.stop() } catch (_: Exception) {}
        val mdns = AdbMdns(this, AdbMdns.TLS_CONNECT) { port ->
            handler.post {
                if (port > 0 && phase == STATE_PAIRED) {
                    phase = STATE_CONNECTING
                    updateNotification(STATE_CONNECTING, "منفذ الاتصال $port — جارٍ التحقق…")
                    verifyConnection(port)
                }
            }
        }
        mdnsConnect = mdns
        mdns.start()
    }

    private fun verifyConnection(port: Int) {
        executor.execute {
            try {
                val key = AdbKeyManager.load(prefs)
                val out = AdbTlsClient.runShell("127.0.0.1", port, key, "echo amino-ok")
                val ok = out.contains("amino-ok")
                LogSystem.log("adb", "connection verify: $out")
                if (ok) {
                    prefs.adbConnectEndpoint = "127.0.0.1:$port"
                    phase = STATE_CONNECTED
                    releaseMulticast()
                    try { mdnsConnect?.stop() } catch (_: Exception) {}
                    endpointListener = null
                    notifyState(STATE_CONNECTED, "ADB متصل — التحكم العميق جاهز")
                    updateNotificationFinal("ADB متصل بنجاح", "منفذ $port — يمكن إغلاق هذا الإشعار")
                    handler.postDelayed({ stopSelf() }, 1500)
                } else {
                    // paired but connection not verified yet — keep state, user can retry later
                    notifyState(STATE_CONNECTING, "تم الاقتران لكن تعذر التحقق من الاتصال — أعد فتح «التصحيح عبر لاسلكي» ثم أعد المحاولة")
                    updateNotificationFinal("ADB مقترن بنجاح", "لم يتم التحقق من الاتصال بعد — أعد المحاولة لاحقًا")
                    handler.postDelayed({ stopSelf() }, 1500)
                }
            } catch (t: Throwable) {
                LogSystem.log("adb", "verify error: ${t.message}")
                notifyState(STATE_CONNECTING, "تعذر الاتصال بالمنفذ $port: ${t.message?.take(100)}")
                updateNotificationFinal("ADB مقترن بنجاح", "تعذر الاتصال بالمنفذ $port — أعد المحاولة لاحقًا")
                handler.postDelayed({ stopSelf() }, 1500)
            }
        }
    }

    // ---------------------------------------------------------- notification

    private fun baseBuilder(state: String, text: String?): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(ai.droidpilot.app.R.drawable.ic_stat_droid)
            .setContentTitle("aMiNo — اقتران ADB")
            .setContentText(text ?: "أدخل رمز الاقتران")
            .setOngoing(state != STATE_CONNECTED)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setSilent(state != STATE_WAITING)

        if (state == STATE_WAITING) {
            val remoteCode = RemoteInput.Builder(KEY_CODE)
                .setLabel("رمز الاقتران (6 أرقام)")
                .build()
            val remoteEndpoint = RemoteInput.Builder(KEY_MANUAL_ENDPOINT)
                .setLabel("IP:منفذ الاقتران — اختياري (يُكتشف تلقائيًا)")
                .build()
            val codeIntent = PendingIntent.getService(
                this, 1,
                Intent(this, AdbPairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val replyAction = NotificationCompat.Action.Builder(0, "اقتران", codeIntent)
                .addRemoteInput(remoteCode)
                .addRemoteInput(remoteEndpoint)
                .setAllowGeneratedReplies(false)
                .build()
            builder.addAction(replyAction)

            val cancelIntent = PendingIntent.getService(
                this, 2,
                Intent(this, AdbPairingService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(NotificationCompat.Action.Builder(0, "إلغاء", cancelIntent).build())
        }

        // FIX: after pairing succeeded, give the user a manual path to enter the
        // CONNECT port — ColorOS often blocks mDNS (_adb-tls-connect._tcp).
        if (state == STATE_PAIRED) {
            val remoteEndpoint = RemoteInput.Builder(KEY_MANUAL_ENDPOINT)
                .setLabel("IP:منفذ الاتصال من شاشة التصحيح اللاسلكي")
                .build()
            val connectIntent = PendingIntent.getService(
                this, 3,
                Intent(this, AdbPairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val connectAction = NotificationCompat.Action.Builder(0, "إدخال منفذ الاتصال", connectIntent)
                .addRemoteInput(remoteEndpoint)
                .setAllowGeneratedReplies(false)
                .build()
            builder.addAction(connectAction)
        }
        return builder
    }

    private fun buildNotification(state: String, text: String?): Notification =
        baseBuilder(state, text).build()

    private fun updateNotification(state: String, text: String) {
        try {
            notifManager.notify(NOTIF_ID, buildNotification(state, text))
        } catch (_: Exception) {}
    }

    /** Final notification: no input actions, dismissible. */
    private fun updateNotificationFinal(title: String, text: String) {
        try {
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(ai.droidpilot.app.R.drawable.ic_stat_droid)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(false)
                .build()
            notifManager.notify(NOTIF_ID, n)
        } catch (_: Exception) {}
    }

    private fun notifyState(state: String, message: String) {
        val intent = Intent(BROADCAST_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_MESSAGE, message)
        try {
            sendBroadcast(intent)
        } catch (_: Exception) {}
        LogSystem.log("adb", "[$state] $message")
    }

    // ----------------------------------------------------------------- stop

    private fun stopEverything() {
        handler.removeCallbacks(timeoutRunnable)
        endpointListener = null
        try {
            ai.droidpilot.app.access.DroidPilotAccessibilityService.instance?.setAdbPairWatching(false)
        } catch (_: Throwable) {}
        try { mdnsPairing?.stop() } catch (_: Exception) {}
        try { mdnsConnect?.stop() } catch (_: Exception) {}
        releaseMulticast()
        stopForeground(false)
        stopSelf()
    }

    override fun onDestroy() {
        endpointListener = null
        try { mdnsPairing?.stop() } catch (_: Exception) {}
        try { mdnsConnect?.stop() } catch (_: Exception) {}
        releaseMulticast()
        super.onDestroy()
    }
}
