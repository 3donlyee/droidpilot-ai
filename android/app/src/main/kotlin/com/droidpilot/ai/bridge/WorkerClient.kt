package com.droidpilot.ai.bridge

import com.droidpilot.ai.device.PairingManager
import com.droidpilot.ai.util.JsonUtil
import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.IOException
import java.util.concurrent.TimeUnit

/**
 * DroidPilot AI — Worker bridge.
 *
 * Responsibilities:
 *  - HTTP/HTTPS POST to `/api/device/register` (pairing)
 *  - HTTPS+WSS long-lived channel for receiving command envelopes and
 *    sending result envelopes
 *
 * Uses OkHttp (single shared client) + kotlinx.serialization.
 *
 * The Worker URL is NOT hardcoded — it is always provided by the caller
 * (read from the UI / SharedPreferences at runtime).
 */
class WorkerClient(
    private val httpClient: OkHttpClient = defaultClient()
) {

    /**
     * Pairing: register the device with the Worker, returning the
     * device_id / device_secret / pairing_code triple.
     */
    @Throws(IOException::class)
    suspend fun registerDevice(
        workerUrl: String,
        request: PairingManager.RegisterRequest
    ): PairingManager.RegisterResponse {
        val url = normalizeHttpsUrl(workerUrl) + "/api/device/register"
        val body = JsonUtil.json.encodeToString(
            PairingManager.RegisterRequest.serializer(),
            request
        )
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        Logger.i(TAG, "registerDevice → POST $url")
        val resp = httpClient.newCall(req).execute()
        resp.use {
            if (!it.isSuccessful) {
                throw IOException("HTTP ${it.code}: ${it.message}")
            }
            val text = it.body?.string().orEmpty()
            Logger.d(TAG, "registerDevice ← ${text.take(200)}")
            return JsonUtil.json.decodeFromString(
                PairingManager.RegisterResponse.serializer(),
                text
            )
        }
    }

    /**
     * Open the long-lived command channel.
     *
     * Returns a cold [Flow] of incoming [CommandEnvelope] objects. The flow:
     *  - opens the WSS connection to `${workerUrl}/api/device/connect`
     *  - emits each inbound command
     *  - completes (or errors) on socket close / failure
     *
     * Caller should also use [sendResult] to push back results on the same
     * socket (see [activeSocket]).
     */
    fun connectCommandChannel(
        workerUrl: String,
        deviceId: String,
        deviceSecret: String,
        onCommand: (CommandEnvelope) -> Unit,
        onStateChange: (ChannelState) -> Unit = {}
    ): CancellableHandle {
        val wsUrl = normalizeWsUrl(workerUrl) +
            "/api/device/connect?device_id=$deviceId&device_secret=$deviceSecret"

        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.i(TAG, "WSS open")
                activeSocket = webSocket
                onStateChange(ChannelState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val env = parseCommand(text)
                    if (env != null) {
                        onCommand(env)
                    } else {
                        Logger.w(TAG, "WSS unknown frame: ${text.take(160)}")
                    }
                } catch (t: Throwable) {
                    Logger.e(TAG, "WSS parse failed: $t")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Logger.i(TAG, "WSS closing: $code $reason")
                onStateChange(ChannelState.CLOSING)
                webSocket.close(1000, "bye")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logger.i(TAG, "WSS closed: $code $reason")
                activeSocket = null
                onStateChange(ChannelState.DISCONNECTED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.e(TAG, "WSS failure: $t")
                activeSocket = null
                onStateChange(ChannelState.FAILED(t.message ?: t.javaClass.simpleName))
            }
        }

        Logger.i(TAG, "WSS connecting to $wsUrl")
        onStateChange(ChannelState.CONNECTING)
        val socket = httpClient.newWebSocket(request, listener)
        return CancellableHandle { socket.cancel() }
    }

    /**
     * Push a [ResultEnvelope] back to the Worker on the active socket.
     * Returns true if queued by the underlying client, false on failure.
     */
    fun sendResult(envelope: ResultEnvelope): Boolean {
        val socket = activeSocket ?: run {
            Logger.w(TAG, "sendResult: no active socket")
            return false
        }
        val payload = JsonUtil.json.encodeToString(
            ResultEnvelope.serializer(),
            envelope
        )
        val ok = socket.send(payload)
        if (!ok) Logger.w(TAG, "sendResult: socket.send returned false")
        return ok
    }

    // --------------------------------------------------------------------------
    // Types
    // --------------------------------------------------------------------------

    /** Live handle for the open WebSocket (null if not connected). */
    @Volatile
    private var activeSocket: WebSocket? = null

    /** Allows the caller to cancel the WSS connection. */
    class CancellableHandle(val cancelFn: () -> Unit) {
        fun cancel() = cancelFn()
    }

    sealed class ChannelState {
        object CONNECTING : ChannelState()
        object CONNECTED : ChannelState()
        object CLOSING : ChannelState()
        object DISCONNECTED : ChannelState()
        data class FAILED(val reason: String) : ChannelState()
    }

    companion object {
        private const val TAG = "WorkerClient"
        private const val USER_AGENT = "DroidPilot-AI/1.0 (Android)"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // WSS = long lived
            .pingInterval(20, TimeUnit.SECONDS) // keepalive
            .build()

        /**
         * Ensure the URL starts with https:// — strip trailing slash.
         * We REFUSE http:// (usesCleartextTraffic=false).
         */
        fun normalizeHttpsUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.startsWith("http://")) {
                u = "https://" + u.substring("http://".length)
            }
            if (!u.startsWith("https://")) {
                u = "https://$u"
            }
            return u
        }

        /**
         * Convert https://... → wss://...
         */
        fun normalizeWsUrl(httpsUrl: String): String {
            var u = normalizeHttpsUrl(httpsUrl)
            if (u.startsWith("https://")) {
                u = "wss://" + u.substring("https://".length)
            }
            return u
        }

        /**
         * Parse an inbound command frame:
         *   { "type":"command", "id":"cmd_001", "tool":"swipe_up", "arguments":{...} }
         */
        fun parseCommand(text: String): CommandEnvelope? {
            return try {
                val element = JsonUtil.json.parseToJsonElement(text)
                if (element !is JsonObject) return null
                val type = (element["type"] as? JsonPrimitive)?.contentOrNull() ?: return null
                if (type != "command") return null
                val id = (element["id"] as? JsonPrimitive)?.contentOrNull()
                    ?: return null
                val tool = (element["tool"] as? JsonPrimitive)?.contentOrNull()
                    ?: return null
                val arguments = element["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                CommandEnvelope(id = id, tool = tool, arguments = arguments)
            } catch (t: Throwable) {
                Logger.w(TAG, "parseCommand failed: $t")
                null
            }
        }
    }
}

// Small helper to extract a primitive string content or null.
internal fun JsonPrimitive.contentOrNull(): String? = if (this.isString) this.content else null

/**
 * Inbound command from the Worker.
 *
 * Example: `{ "type":"command", "id":"cmd_001", "tool":"swipe_up", "arguments":{} }`
 */
@kotlinx.serialization.Serializable
data class CommandEnvelope(
    val id: String,
    val tool: String,
    val arguments: JsonObject
)

/**
 * Outbound result to the Worker.
 *
 * Example:
 * `{ "type":"result", "id":"cmd_001", "success":true, "data":{...} }`
 */
@kotlinx.serialization.Serializable
data class ResultEnvelope(
    val type: String = "result",
    val id: String,
    val success: Boolean,
    val data: JsonObject? = null,
    val error: ErrorBody? = null
) {
    @kotlinx.serialization.Serializable
    data class ErrorBody(
        val code: String,
        val message: String
    )
}
