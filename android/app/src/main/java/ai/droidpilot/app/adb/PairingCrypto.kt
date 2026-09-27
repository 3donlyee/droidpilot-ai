package ai.droidpilot.app.adb

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

/**
 * SPAKE2 port of boringssl crypto/curve25519/spake25519.cc — the exact PAKE
 * used by AOSP adb wireless pairing (adb pairing_auth).
 *
 * Pure JVM (no android.* imports) so it can be verified by JVM unit tests
 * against deterministic reference vectors.
 */
internal class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray
) {

    enum class Role { ALICE, BOB }

    private var privateKey: ByteArray? = null   // 32-byte LE, < 8L
    private var passwordHash: ByteArray? = null // 64-byte SHA512(password)
    private var passwordScalar: ByteArray? = null // 32-byte LE (cofactor-cleared)
    private var myMsg: ByteArray? = null

    /** SPAKE2_generate_msg → our 32-byte public message. */
    fun generateMsg(password: ByteArray): ByteArray {
        val rand = ByteArray(64)
        SecureRandom().nextBytes(rand)
        return generateMsg(password, rand)
    }

    /** Deterministic variant (unit tests). */
    fun generateMsg(password: ByteArray, rand64: ByteArray): ByteArray {
        // private key: sc_reduce(rand64) << 3 — boringssl reduces then multiplies by 8
        val privRed = Ed25519.scReduce(rand64)
        val priv = Ed25519.toLe(Ed25519.fromLe(privRed).shiftLeft(3), 32)
        privateKey = priv

        // password hash + scalar
        val pHash = Ed25519.sha512(password)
        passwordHash = pHash
        var p = Ed25519.fromLe(Ed25519.scReduce(pHash))

        // cofactor fix (boringssl "password scalar hack"): make p ≡ 0 (mod 8)
        val L = Ed25519.L
        if (p.testBit(0)) p = p.add(L)
        if (p.testBit(1)) p = p.add(L.shiftLeft(1))
        if (p.testBit(2)) p = p.add(L.shiftLeft(2))
        val pScalar = Ed25519.toLe(p, 32)
        passwordScalar = pScalar

        val maskPt = Ed25519.decodeOrThrow(if (role == Role.ALICE) M_ENCODE else N_ENCODE)
        val mask = Ed25519.scalarMult(pScalar, maskPt)
        val p0 = Ed25519.scalarMult(priv, Ed25519.BASE)
        val pstar = Ed25519.add(p0, mask)
        val msg = Ed25519.encode(pstar)
        myMsg = msg
        return Arrays.copyOf(msg, msg.size)
    }

    /** SPAKE2_process_msg → shared key material (64 bytes). */
    fun processMsg(theirMsg: ByteArray): ByteArray {
        val my = myMsg ?: throw IllegalStateException("generateMsg must be called first")
        if (theirMsg == null || theirMsg.size != 32) throw IllegalArgumentException("bad msg length")
        val qstar = Ed25519.decode(theirMsg)
            ?: throw IllegalArgumentException("peer point not on curve")

        val peersMaskPt = Ed25519.decodeOrThrow(if (role == Role.ALICE) N_ENCODE else M_ENCODE)
        val peersMask = Ed25519.scalarMult(passwordScalar!!, peersMaskPt)
        val q = Ed25519.sub(qstar, peersMask)
        val dh = Ed25519.scalarMult(privateKey!!, q)
        val dhEncoded = Ed25519.encode(dh)

        val sha = MessageDigest.getInstance("SHA-512")
        val alice = role == Role.ALICE
        update(sha, if (alice) myName else theirName)
        update(sha, if (alice) theirName else myName)
        update(sha, if (alice) my else theirMsg)
        update(sha, if (alice) theirMsg else my)
        update(sha, dhEncoded)
        update(sha, passwordHash!!)
        return sha.digest()
    }

    companion object {
        internal val M_ENCODE = Ed25519.hexToBytes(
            "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"
        )
        internal val N_ENCODE = Ed25519.hexToBytes(
            "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"
        )

        private fun update(sha: MessageDigest, data: ByteArray) {
            val len = ByteArray(8)
            var l = data.size.toLong()
            for (i in 0 until 8) {
                len[i] = (l and 0xff).toByte()
                l = l shr 8
            }
            sha.update(len)
            sha.update(data)
        }
    }
}

/**
 * Port of AOSP adb pairing_auth AES layer:
 * HKDF-SHA256(key_material) → AES-128-GCM with little-endian 64-bit
 * sequence numbers zero-padded into 12-byte nonces.
 */
internal class PairingCrypto(keyMaterial: ByteArray) {

    private val key: ByteArray
    private var encSequence: Long = 0
    private var decSequence: Long = 0

    init {
        val info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
        key = hkdf(keyMaterial, null, info, 16)
    }

    companion object {
        /** RFC 5869 HKDF (salt == null → zero-filled salt). */
        fun hkdf(ikm: ByteArray, saltIn: ByteArray?, info: ByteArray, len: Int): ByteArray {
            try {
                val salt = saltIn ?: ByteArray(32)
                var hmac = Mac.getInstance("HmacSHA256")
                hmac.init(SecretKeySpec(salt, "HmacSHA256"))
                val prk = hmac.doFinal(ikm)

                val out = ByteArray(len)
                var t = ByteArray(0)
                var pos = 0
                var counter = 1
                while (pos < len) {
                    hmac = Mac.getInstance("HmacSHA256")
                    hmac.init(SecretKeySpec(prk, "HmacSHA256"))
                    hmac.update(t)
                    hmac.update(info)
                    hmac.update(counter.toByte())
                    t = hmac.doFinal()
                    val n = minOf(t.size, len - pos)
                    System.arraycopy(t, 0, out, pos, n)
                    pos += n
                    counter++
                }
                return out
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }

    private fun nonce(seq: Long): ByteArray {
        val n = ByteArray(12)
        var s = seq
        for (i in 0 until 8) {
            n[i] = (s and 0xff).toByte()
            s = s shr 8
        }
        return n
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(encSequence)))
            val out = c.doFinal(plaintext)
            encSequence++
            return out
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /** Returns null on authentication failure (e.g. wrong pairing code). */
    fun decrypt(ciphertext: ByteArray): ByteArray? {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(decSequence)))
            val out = c.doFinal(ciphertext)
            decSequence++
            return out
        } catch (e: Exception) {
            return null
        }
    }
}
