package ai.droidpilot.app.adb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

class AdbInvalidPairingCodeException : Exception("pairing code rejected (decrypt failed)")

/**
 * Real ADB wireless-debugging PAIRING client (Android 11+).
 *
 * Port of Shizuku's AdbPairingClient (Apache-2.0) which itself follows AOSP
 * packages/modules/adb pairing_connection:
 *
 *   1. TLS 1.3 channel with our RSA client certificate (adbd's pairing server
 *      accepts any cert — the 6-digit pairing code is the real authentication).
 *   2. TLS exporter "adb-label\0" → 64 bytes of channel binding.
 *   3. password = pairingCode || exportedKeyMaterial
 *   4. SPAKE2 (curve25519) exchange in PairingPackets {ver:u8, type:u8, payload:u32 BE}
 *   5. AES-128-GCM encrypted PeerInfo exchange (8192-byte struct carrying our
 *      adb public key line) — adbd stores the key as an authorized adb client.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    pairCode: String,
    private val key: AdbKeyManager
) : Closeable {

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null

    private val codeBytes: ByteArray

    private val peerInfo: ByteArray = ByteArray(K_MAX_PEER_INFO_SIZE)
    private var spakeMsg: ByteArray? = null
    private var cipher: PairingCrypto? = null
    private var spake2Ref: Spake2? = null

    init {
        // PeerInfo: type byte 0 (ADB_RSA_PUB_KEY) + the adb public key line, zero padded.
        val pub = key.adbPublicKey
        peerInfo[0] = 0
        System.arraycopy(pub, 0, peerInfo, 1, minOf(pub.size, K_MAX_PEER_INFO_SIZE - 1))
        codeBytes = pairCode.toByteArray(Charsets.US_ASCII)
    }

    /** Runs the full pairing handshake. Returns true on success. */
    fun start(): Boolean {
        setupTlsConnection()

        if (!doExchangeMsgs()) return false
        if (!doExchangePeerInfo()) return false
        return true
    }

    private fun setupTlsConnection() {
        val s = Socket(host, port)
        s.tcpNoDelay = true
        socket = s

        val ssl = key.sslContext.socketFactory.createSocket(s, host, port, true) as SSLSocket
        ssl.startHandshake()
        Log.d(TAG, "pairing TLS handshake succeeded")

        inputStream = DataInputStream(ssl.inputStream)
        outputStream = DataOutputStream(ssl.outputStream)

        // TLS exporter — platform Conscrypt hidden API, accessed via reflection.
        val keyMaterial = exportKeyingMaterial(ssl, K_EXPORTED_KEY_LABEL, K_EXPORTED_KEY_SIZE)
            ?: throw IllegalStateException("Conscrypt.exportKeyingMaterial unavailable")

        // password = pairing code || exported key material (AOSP pairing_connection.cpp)
        val password = ByteArray(codeBytes.size + keyMaterial.size)
        codeBytes.copyInto(password)
        keyMaterial.copyInto(password, codeBytes.size)

        val spake2 = Spake2(Spake2.Role.ALICE, K_CLIENT_NAME, K_SERVER_NAME)
        spakeMsg = spake2.generateMsg(password)
        spake2Ref = spake2
    }

    private fun doExchangeMsgs(): Boolean {
        val ctx = spake2Ref ?: return false
        val msg = spakeMsg ?: return false
        writeHeader(TYPE_SPAKE2_MSG, msg.size)
        outputStream!!.write(msg)
        outputStream!!.flush()

        val header = readHeader() ?: return false
        if (header.second != TYPE_SPAKE2_MSG) return false
        val their = ByteArray(header.first)
        inputStream!!.readFully(their)

        val keyMaterial = ctx.processMsg(their)
        cipher = PairingCrypto(keyMaterial)
        return true
    }

    private fun doExchangePeerInfo(): Boolean {
        val out = cipher!!.encrypt(peerInfo)
        writeHeader(TYPE_PEER_INFO, out.size)
        outputStream!!.write(out)
        outputStream!!.flush()

        val header = readHeader() ?: return false
        if (header.second != TYPE_PEER_INFO) return false
        val their = ByteArray(header.first)
        inputStream!!.readFully(their)

        val decrypted = cipher!!.decrypt(their) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != K_MAX_PEER_INFO_SIZE) {
            Log.e(TAG, "unexpected PeerInfo size: " + decrypted.size)
            return false
        }
        return true
    }

    private fun writeHeader(type: Int, payloadSize: Int) {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buf.put(K_VERSION)
        buf.put(type.toByte())
        buf.putInt(payloadSize)
        outputStream!!.write(buf.array())
    }

    /** Returns (payloadSize, type) or null when the header is invalid. */
    private fun readHeader(): Pair<Int, Int>? {
        val bytes = ByteArray(6)
        inputStream!!.readFully(bytes)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get()
        val type = buf.get().toInt() and 0xFF
        val payload = buf.int
        if (version != K_VERSION) {
            Log.e(TAG, "PairingPacketHeader version mismatch: $version")
            return null
        }
        if (type != TYPE_SPAKE2_MSG && type != TYPE_PEER_INFO) return null
        if (payload <= 0 || payload > K_MAX_PAYLOAD_SIZE) return null
        return Pair(payload, type)
    }

    override fun close() {
        try { inputStream?.close() } catch (_: Throwable) {}
        try { outputStream?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    companion object {
        const val TAG = "AdbPairingClient"

        const val K_VERSION: Byte = 1
        const val TYPE_SPAKE2_MSG = 0
        const val TYPE_PEER_INFO = 1

        const val K_MAX_PEER_INFO_SIZE = 8192
        const val K_MAX_PAYLOAD_SIZE = K_MAX_PEER_INFO_SIZE * 2

        const val K_EXPORTED_KEY_LABEL = "adb-label\u0000"
        const val K_EXPORTED_KEY_SIZE = 64

        /** boringssl SPAKE2 identity strings — sizeof() includes the trailing NUL. */
        val K_CLIENT_NAME: ByteArray = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        val K_SERVER_NAME: ByteArray = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        /**
         * Conscrypt.exportKeyingMaterial(socket, label, context, length).
         * Present in the platform TLS provider on API 29+; Shizuku calls the
         * same API (compile-time there, reflection here to avoid stub jars).
         */
        fun exportKeyingMaterial(ssl: SSLSocket, label: String, length: Int): ByteArray? = try {
            val c = Class.forName("com.android.org.conscrypt.Conscrypt")
            val m = c.getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            m.invoke(null, ssl, label, null, length) as? ByteArray
        } catch (t: Throwable) {
            Log.w(TAG, "exportKeyingMaterial failed: ${t.message}")
            null
        }
    }
}
