package ai.droidpilot.app.adb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * ADB wire protocol (CNXN/AUTH/OPEN/OKAY/WRTE/CLSE + STLS upgrade) with the
 * same framing Shizuku uses for wireless-debugging connections.
 *
 * Flow (Android 11+ wireless debugging): CNXN → adbd answers A_STLS →
 * TLS 1.3 handshake with our paired client certificate → CNXN →
 * OPEN("shell:…") → OKAY → WRTE* → CLSE.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbTlsClient private constructor(
    private val host: String,
    private val port: Int,
    private val key: AdbKeyManager
) : Closeable {

    companion object {
        const val TAG = "AdbTlsClient"

        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x534c5453

        const val A_VERSION = 0x01000000
        const val A_MAXDATA = 1 shl 20
        const val A_STLS_VERSION = 0x01000000

        const val ADB_AUTH_TOKEN = 1
        const val ADB_AUTH_SIGNATURE = 2
        const val ADB_AUTH_RSAPUBLICKEY = 3

        /**
         * One-shot helper: connect, run a shell command, return its output.
         * Used by the pairing service to verify the connection and by
         * AdbBridge as the deep-control path.
         */
        fun runShell(host: String, port: Int, key: AdbKeyManager, command: String): String {
            val c = AdbTlsClient(host, port, key)
            try {
                c.connect()
                val out = StringBuilder()
                c.shellCommand(command) { chunk -> out.append(String(chunk, Charsets.UTF_8)) }
                return out.toString()
            } finally {
                try { c.close() } catch (_: Exception) {}
            }
        }
    }

    private var socket: Socket? = null
    private var plainIn: DataInputStream? = null
    private var plainOut: DataOutputStream? = null
    private var tlsSocket: SSLSocket? = null
    private var useTls = false

    private val input: DataInputStream
        get() = if (useTls) DataInputStream(tlsSocket!!.inputStream) else plainIn!!
    private val output: DataOutputStream
        get() = if (useTls) DataOutputStream(tlsSocket!!.outputStream) else plainOut!!

    fun connect() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 8000)
        s.soTimeout = 15000
        socket = s
        plainIn = DataInputStream(s.getInputStream())
        plainOut = DataOutputStream(s.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::".toByteArray(Charsets.UTF_8))

        var message = read()
        if (message.command == A_STLS) {
            write(A_STLS, A_STLS_VERSION, 0, null as ByteArray?)
            val tls = key.sslContext.socketFactory.createSocket(s, host, port, true) as SSLSocket
            tls.startHandshake()
            Log.d(TAG, "TLS handshake succeeded")
            tlsSocket = tls
            useTls = true
            message = read()
        } else if (message.command == A_AUTH) {
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))
            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }
        if (message.command != A_CNXN) throw IllegalStateException("expected A_CNXN, got 0x" + Integer.toHexString(message.command))
    }

    fun shellCommand(command: String, listener: (ByteArray) -> Unit) {
        val localId = 1
        val payload = ("shell:$command\u0000").toByteArray(Charsets.UTF_8)
        write(A_OPEN, localId, 0, payload)

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        if (message.data != null && message.data.isNotEmpty()) {
                            listener(message.data)
                        }
                        write(A_OKAY, localId, remoteId, null as ByteArray?)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId, null as ByteArray?)
                        break
                    } else {
                        throw IllegalStateException("expected A_WRTE/A_CLSE, got 0x" + Integer.toHexString(message.command))
                    }
                }
            }
            A_CLSE -> {
                write(A_CLSE, localId, message.arg0, null as ByteArray?)
            }
            else -> throw IllegalStateException("expected A_OKAY/A_CLSE, got 0x" + Integer.toHexString(message.command))
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray?) {
        output.write(AdbMessage(command, arg0, arg1, data).toByteArray())
        output.flush()
    }

    private fun read(): AdbMessage {
        val buf = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        input.readFully(buf.array(), 0, AdbMessage.HEADER_LENGTH)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLength = buf.int
        val checksum = buf.int
        val magic = buf.int
        val data: ByteArray?
        if (dataLength >= 0) {
            data = ByteArray(dataLength)
            input.readFully(data, 0, dataLength)
        } else {
            data = null
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        return message
    }

    override fun close() {
        try { tlsSocket?.close() } catch (_: Exception) {}
        try { plainIn?.close() } catch (_: Throwable) {}
        try { plainOut?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}

/** ADB frame: 24-byte little-endian header + payload. */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataChecksum: Int,
    val magic: Int,
    val data: ByteArray?
) {

    constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) : this(
        command,
        arg0,
        arg1,
        data?.size ?: 0,
        checksum(data),
        (command.toLong() xor 0xFFFFFFFFL).toInt(),
        data
    )

    fun validate(): Boolean {
        if (command != (magic xor -0x1)) return false
        if (dataLength != 0 && checksum(data) != dataChecksum) return false
        return true
    }

    fun validateOrThrow() {
        if (!validate()) throw IllegalArgumentException("bad ADB message: cmd=0x" + Integer.toHexString(command))
    }

    fun toByteArray(): ByteArray {
        val length = HEADER_LENGTH + (data?.size ?: 0)
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(dataLength)
            .putInt(dataChecksum)
            .putInt(magic)
            .apply { if (data != null) put(data) }
            .array()
    }

    companion object {
        const val HEADER_LENGTH = 24

        /** adb checksum = simple sum of bytes (NOT CRC32). */
        fun checksum(data: ByteArray?): Int {
            if (data == null) return 0
            var res = 0L
            for (b in data) res += (b.toInt() and 0xFF)
            return res.toInt()
        }
    }
}
