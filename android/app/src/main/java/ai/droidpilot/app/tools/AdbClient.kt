package ai.droidpilot.app.tools

import ai.droidpilot.app.core.LogSystem
import ai.droidpilot.app.core.SecurePrefs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Minimal classic ADB client (TCP, port 5555) — Aiminos deep-control layer.
 *
 * One-time setup (PC required, 30 seconds):
 *   1. enable USB debugging + run `adb tcpip 5555`
 *   2. then in-app: ADB mode ON → authorize the RSA dialog when it appears
 *
 * Protocol: CNXN → AUTH(token/signature/[publickey]) → OPEN("shell:…") → OKAY → WRTE* → CLSE.
 * Only shell commands are used (input tap/swipe/text) — a fallback when the
 * accessibility layer cannot reach a stubborn view. Everything is logged.
 */
class AdbClient private constructor(
    private val socket: Socket,
    private val inS: BufferedInputStream,
    private val outS: BufferedOutputStream
) {
    companion object {
        // FIX: ADB command constants are the ASCII command strings read as
        // LITTLE-ENDIAN u32 (AOSP protocol.h): "CNXN" = 0x4e584e43. The previous
        // big-endian readings produced "NXNC"-style garbage on the wire.
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_WRTE = 0x45545257
        const val A_CLSE = 0x45534c43
        const val A_VERSION = 0x01000001
        const val MAX_PAYLOAD = 4096

        /** ADB checksum = plain additive byte-sum (NOT CRC32 — see AOSP adb_client.c). */
        private fun checksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) sum += b.toInt() and 0xFF
            return sum
        }

        private fun le32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun putLE32(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte(); b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        /** Connect + authenticate using the RSA keypair stored in SecurePrefs. */
        fun connect(prefs: SecurePrefs, port: Int = 5555, timeoutMs: Int = 8000): AdbClient {
            val (privB64, pubB64) = AdbBridge.ensureKeys(prefs)
            val priv = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)))

            val sock = Socket()
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            val c = AdbClient(
                sock,
                BufferedInputStream(sock.getInputStream(), 64 * 1024),
                BufferedOutputStream(sock.getOutputStream(), 16 * 1024)
            )
            try {
                c.writeMsg(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::features=".toByteArray())

                var pubkeySent = false
                while (true) {
                    val m = c.readMsg()
                    when (m.cmd) {
                        A_CNXN -> return c
                        A_AUTH -> when (m.arg0) {
                            1 -> { // TOKEN
                                val sig = Signature.getInstance("SHA1withRSA")
                                sig.initSign(priv)
                                sig.update(m.data)
                                c.writeMsg(A_AUTH, 2, 0, sig.sign())
                            }
                            3 -> { // RSAPUBLICKEY → system authorization dialog appears
                                if (!pubkeySent) {
                                    val pubPacket = "$pubB64\u0000".toByteArray(Charsets.UTF_8)
                                    c.writeMsg(A_AUTH, 3, 0, pubPacket)
                                    pubkeySent = true
                                    LogSystem.log("adb", "public key sent — approve the dialog on screen")
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                try { c.close() } catch (_: Exception) {}
                throw IOException("adb connect failed: ${t.message}")
            }
        }
    }

    class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun readMsg(): Msg {
        val h = ByteArray(24)
        var off = 0
        while (off < 24) {
            val n = inS.read(h, off, 24 - off)
            if (n < 0) throw IOException("adb stream closed (header)")
            off += n
        }
        val cmd = le32(h, 0); val arg0 = le32(h, 4); val arg1 = le32(h, 8)
        val len = le32(h, 12); val check = le32(h, 16); val magic = le32(h, 20)
        if (magic != cmd.inv()) throw IOException("adb bad magic")
        val data = if (len > 0) {
            val d = ByteArray(len); var o2 = 0
            while (o2 < len) {
                val n = inS.read(d, o2, len - o2)
                if (n < 0) throw IOException("adb stream closed (payload)")
                o2 += n
            }
            if (checksum(d) != check) throw IOException("adb checksum mismatch")
            d
        } else ByteArray(0)
        return Msg(cmd, arg0, arg1, data)
    }

    private fun writeMsg(cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val h = ByteArray(24)
        putLE32(h, 0, cmd); putLE32(h, 4, arg0); putLE32(h, 8, arg1)
        putLE32(h, 12, data.size)
        putLE32(h, 16, checksum(data))
        putLE32(h, 20, cmd.inv())
        outS.write(h); outS.write(data); outS.flush()
    }

    /** Run one shell command and return its stdout (bounded). */
    fun shell(command: String, timeoutMs: Int = 15000): String {
        socket.soTimeout = timeoutMs
        val localId = 1
        writeMsg(A_OPEN, localId, 0, "shell:$command".toByteArray())
        var remoteId = -1
        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val m = readMsg()
            when (m.cmd) {
                A_OKAY -> remoteId = m.arg0
                A_WRTE -> {
                    out.append(String(m.data, Charsets.UTF_8))
                    if (remoteId > 0) writeMsg(A_OKAY, localId, remoteId, ByteArray(0))
                    if (out.length > 128_000) return out.toString()
                }
                A_CLSE -> return out.toString()
            }
        }
        throw IOException("adb shell timeout")
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}
