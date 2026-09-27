package ai.droidpilot.app.adb

import android.annotation.SuppressLint
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import ai.droidpilot.app.core.LogSystem
import ai.droidpilot.app.core.SecurePrefs
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * ADB RSA key + self-signed X509 certificate + TLS 1.3 context.
 * Port of Shizuku's AdbKey (Apache-2.0), adapted to store the RSA key in
 * the app's existing EncryptedSharedPreferences (SecurePrefs) so the SAME
 * key is shared with the legacy ADB client.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbKeyManager private constructor(private val privateKey: RSAPrivateKey) {

    companion object {
        /** Load (or lazily create) the RSA-2048 key from SecurePrefs. */
        fun load(prefs: SecurePrefs): AdbKeyManager {
            var priv: RSAPrivateKey? = null
            val stored = prefs.adbKeyPriv
            if (!stored.isNullOrBlank()) {
                try {
                    val kf = KeyFactory.getInstance("RSA")
                    priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(stored, Base64.NO_WRAP))) as RSAPrivateKey
                } catch (_: Throwable) {
                    priv = null
                }
            }
            if (priv == null) {
                val kpg = KeyPairGenerator.getInstance("RSA")
                kpg.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), SecureRandom())
                val kp = kpg.generateKeyPair()
                priv = kp.private as RSAPrivateKey
                prefs.adbKeyPriv = Base64.encodeToString(priv.encoded, Base64.NO_WRAP)
                prefs.adbKeyPub = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
                LogSystem.log("adb", "generated new RSA-2048 ADB keypair")
            }
            return AdbKeyManager(priv)
        }
    }

    private val publicKey: RSAPublicKey =
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey

    private val certificate: X509Certificate

    init {
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey as PrivateKey)
        val cert = X509v3CertificateBuilder(
            X500Name("CN=aMiNo"),
            BigInteger.ONE,
            Date(0),
            Date(2461449600000L),
            java.util.Locale.ROOT,
            X500Name("CN=aMiNo"),
            SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        ).build(signer)
        certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(cert.encoded)) as X509Certificate
    }

    /** Android adb pubkey line: base64(RSAPublicKey struct) + " name\0". */
    val adbPublicKey: ByteArray by lazy {
        val r32 = BigInteger.ZERO.setBit(32)
        val n0inv = publicKey.modulus.remainder(r32).modInverse(r32).negate()
        val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
        val rr = r.modPow(BigInteger.valueOf(2), publicKey.modulus)

        val buffer = ByteBuffer.allocate(RSAPublicKey_Size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
        buffer.putInt(n0inv.toInt())
        toAdbWords(publicKey.modulus).forEach { buffer.putInt(it) }
        toAdbWords(rr).forEach { buffer.putInt(it) }
        buffer.putInt(publicKey.publicExponent.toInt())

        val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
        val nameBytes = " aMiNo\u0000".toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(base64Bytes.size + nameBytes.size)
        base64Bytes.copyInto(bytes)
        nameBytes.copyInto(bytes, base64Bytes.size)
        bytes
    }

    /**
     * adb AUTH signature: RSA/ECB/NoPadding with a hand-built PKCS#1 v1.5
     * block (SHA-1 DigestInfo) — exactly like Shizuku's AdbKey.sign().
     */
    fun sign(data: ByteArray?): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(data)
    }

    private val keyManager: X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
        private val alias = "amino"

        override fun chooseClientAlias(
            keyTypes: Array<out String>,
            issuers: Array<out java.security.Principal>?,
            socket: Socket?
        ): String? {
            for (keyType in keyTypes) if (keyType == "RSA") return alias
            return null
        }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            return if (alias == this.alias) arrayOf(certificate) else null
        }

        override fun getPrivateKey(alias: String?): PrivateKey? {
            return if (alias == this.alias) privateKey else null
        }

        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
    }

    @SuppressLint("TrustAllX509TrustManager")
    private val trustManager: X509ExtendedTrustManager = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val sslContext: SSLContext by lazy {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        ctx
    }

    private fun toAdbWords(v: BigInteger): IntArray {
        val encoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
        val r32 = BigInteger.ZERO.setBit(32)
        var tmp = v.add(BigInteger.ZERO)
        for (i in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
            val out = tmp.divideAndRemainder(r32)
            tmp = out[0]
            encoded[i] = out[1].toInt()
        }
        return encoded
    }

    /** RSA/ECB/NoPadding PKCS#1 v1.5 block for SHA-1 (adb AUTH token signing).
     *  236 bytes: 0x00 0x01 FF×218 0x00 DigestInfo(15) — the 20-byte token completes the 256-byte block. */
    private val PADDING: ByteArray = run {
        val b = ByteArray(236)
        b[0] = 0x00
        b[1] = 0x01
        for (i in 2..219) b[i] = 0xFF.toByte()
        b[220] = 0x00
        val di = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        System.arraycopy(di, 0, b, 221, 15)
        b
    }
}
