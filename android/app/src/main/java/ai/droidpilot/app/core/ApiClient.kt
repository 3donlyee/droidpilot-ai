package ai.droidpilot.app.core

import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Command envelope received from the Worker. */
data class Command(
    val id: String,
    val tool: String,
    val arguments: JSONObject,
    val approved: Boolean
) {
    companion object {
        fun from(o: JSONObject): Command = Command(
            id = o.optString("id"),
            tool = o.optString("tool"),
            arguments = o.optJSONObject("arguments") ?: JSONObject(),
            approved = o.optBoolean("approved", false)
        )
    }
}

/**
 * HTTPS client for the Cloudflare Worker.
 * Device authentication: x-device-id + x-device-secret headers.
 * The long-poll call uses a client clone with a longer read timeout.
 */
class ApiClient(private val prefs: SecurePrefs) {

    private val json = "application/json; charset=utf-8".toMediaType()

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun longPoll(): OkHttpClient =
        base.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()

    private fun requireUrl(): String {
        val raw = prefs.baseUrl?.trim().orEmpty()
        require(raw.isNotEmpty()) { "WORKER_URL_NOT_SET" }
        val url = if (raw.endsWith("/")) raw.dropLast(1) else raw
        if (url.startsWith("http://") && !prefs.allowInsecureLocal) {
            throw IllegalStateException("INSECURE_URL_REJECTED")
        }
        return url
    }

    private fun auth(builder: Request.Builder) {
        builder.addHeader("x-device-id", prefs.deviceId ?: "")
        builder.addHeader("x-device-secret", prefs.deviceSecret ?: "")
        builder.addHeader("user-agent", "DroidPilot-Android/0.1")
    }

    private fun execute(builder: Request.Builder, long: Boolean): Pair<Int, JSONObject?> {
        val client = if (long) longPoll() else base
        client.newCall(builder.build()).execute().use { resp ->
            val code = resp.code
            val body = resp.body?.string().orEmpty()
            return if (body.isBlank()) code to null
            else code to JSONObject(body)
        }
    }

    val isConfigured: Boolean
        get() = !prefs.baseUrl.isNullOrBlank() && !prefs.deviceId.isNullOrBlank() && !prefs.deviceSecret.isNullOrBlank()

    /** Bootstrap: register this device with the Worker and receive pairing credentials. */
    fun register(): JSONObject {
        val body = JSONObject()
            .put("device_name", Build.MODEL ?: "unknown")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android_version", Build.VERSION.RELEASE ?: "")
            .put("app_version", "0.1.0")
        val req = Request.Builder()
            .url(requireUrl() + "/api/device/register")
            .post(body.toString().toRequestBody(json))
        val (code, resp) = execute(req, long = false)
        require(code == 200 && resp != null) { "REGISTER_FAILED_HTTP_$code" }
        require(resp.optBoolean("ok")) { "REGISTER_FAILED: ${resp.optString("error")}" }
        prefs.deviceId = resp.getString("device_id")
        prefs.deviceSecret = resp.getString("device_secret")
        prefs.deviceName = Build.MODEL
        prefs.isPaired = false
        LogSystem.log("api", "registered as ${resp.getString("device_id")}")
        return resp
    }

    /** Long-poll for a pending command. Returns null when nothing is queued. */
    fun poll(waitSeconds: Int = 20): Command? {
        val req = Request.Builder().url("${requireUrl()}/api/device/poll?wait=$waitSeconds")
        auth(req)
        val (code, resp) = execute(req, long = true)
        if (code == 204 || resp == null) return null
        if (code != 200) throw IllegalStateException("POLL_HTTP_$code")
        return if ("command" == resp.optString("type")) Command.from(resp) else null
    }

    /** Report the result of an executed command back to the Worker. */
    fun postResult(cmd: Command, result: JSONObject): Boolean {
        val payload = result.put("id", cmd.id).put("type", "result")
        val req = Request.Builder()
            .url("${requireUrl()}/api/device/result")
            .post(payload.toString().toRequestBody(json))
        auth(req)
        val (code, resp) = execute(req, long = false)
        return code == 200 && resp?.optBoolean("ok") == true
    }

    /** Optional heartbeat with fresh device info. */
    fun heartbeat(info: JSONObject): Boolean {
        val req = Request.Builder()
            .url("${requireUrl()}/api/device/heartbeat")
            .post(info.toString().toRequestBody(json))
        auth(req)
        val (code, resp) = execute(req, long = false)
        return code == 200 && resp?.optBoolean("ok") == true
    }
}
