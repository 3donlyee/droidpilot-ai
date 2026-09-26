package com.example.network

import com.example.data.model.DeviceInfo
import com.example.data.model.ToolCommand
import com.example.data.model.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class PairResponse(
    val success: Boolean,
    val token: String? = null,
    val message: String? = null
)

data class ChatResponse(
    val success: Boolean,
    val reply: String,
    val executedTools: List<String> = emptyList(),
    val error: String? = null
)

class CloudflareClient(
    private var baseUrl: String = "https://droidpilot-worker.turkjgastroenterol.workers.dev"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun setBaseUrl(url: String) {
        baseUrl = url.trimEnd('/')
    }

    fun getBaseUrl(): String = baseUrl

    suspend fun pairDevice(info: DeviceInfo, secret: String): PairResponse = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "deviceId" to info.deviceId,
            "pairingCode" to info.pairingCode,
            "deviceSecret" to secret,
            "model" to info.model,
            "androidVersion" to info.androidVersion
        )
        val bodyJson = moshi.adapter(Map::class.java).toJson(payload)
        val request = Request.Builder()
            .url("$baseUrl/api/device/pair")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val map = moshi.adapter(Map::class.java).fromJson(body)
                    PairResponse(
                        success = map?.get("success") as? Boolean ?: true,
                        token = map?.get("token") as? String,
                        message = map?.get("message") as? String ?: "Paired successfully"
                    )
                } else {
                    PairResponse(
                        success = false,
                        message = "HTTP ${response.code}: $body"
                    )
                }
            }
        } catch (e: Exception) {
            PairResponse(success = false, message = "Connection error: ${e.message}")
        }
    }

    suspend fun pollCommands(deviceId: String, secret: String): List<ToolCommand> = withContext(Dispatchers.IO) {
        val payload = mapOf("deviceId" to deviceId, "deviceSecret" to secret)
        val bodyJson = moshi.adapter(Map::class.java).toJson(payload)
        val request = Request.Builder()
            .url("$baseUrl/api/device/poll")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val listType = Types.newParameterizedType(List::class.java, ToolCommand::class.java)
                val adapter = moshi.adapter<List<ToolCommand>>(listType)
                adapter.fromJson(body) ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun sendResult(result: ToolResult): Boolean = withContext(Dispatchers.IO) {
        val adapter = moshi.adapter(ToolResult::class.java)
        val bodyJson = adapter.toJson(result)
        val request = Request.Builder()
            .url("$baseUrl/api/device/result")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sendChat(message: String, modelId: String, deviceId: String): ChatResponse = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "message" to message,
            "model" to modelId,
            "deviceId" to deviceId
        )
        val bodyJson = moshi.adapter(Map::class.java).toJson(payload)
        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val map = moshi.adapter(Map::class.java).fromJson(body)
                    @Suppress("UNCHECKED_CAST")
                    ChatResponse(
                        success = map?.get("success") as? Boolean ?: true,
                        reply = map?.get("reply") as? String ?: body,
                        executedTools = (map?.get("tools") as? List<String>) ?: emptyList()
                    )
                } else {
                    ChatResponse(
                        success = false,
                        reply = "",
                        error = "HTTP ${response.code}: $body"
                    )
                }
            }
        } catch (e: Exception) {
            ChatResponse(success = false, reply = "", error = "Network error: ${e.message}")
        }
    }
}
