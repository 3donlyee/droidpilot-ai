package ai.droidpilot.app.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

/**
 * JVM verification of the ADB wireless-pairing crypto core against
 * deterministic reference vectors produced by an independent Python
 * implementation of boringssl's spake25519 (the algorithm used by AOSP adb).
 *
 * The Python reference itself was validated against the RFC 8032 Ed25519
 * test vector before generating these values.
 */
class PairingCryptoTest {

    private fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        return out
    }

    private fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder()
        for (v in b) sb.append(String.format("%02x", v))
        return sb.toString()
    }

    private val clientName = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
    private val serverName = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

    private fun password(): ByteArray {
        val code = "123456".toByteArray(Charsets.US_ASCII)
        val rest = ByteArray(64) { i -> (0xA0 + i).toByte() }
        return code + rest
    }

    private fun seedA(): ByteArray = ByteArray(64) { i -> (0x11 * (i % 7 + 1)).toByte() }
    private fun seedB(): ByteArray = ByteArray(64) { i -> (0x07 * (i % 5 + 2)).toByte() }

    @Test
    fun basePointEncoding() {
        val expected = "58" + "66".repeat(31)
        assertEquals(expected, bytesToHex(Ed25519.encode(Ed25519.BASE)))
    }

    @Test
    fun fixedPointsOnCurve() {
        assertNotNull(Ed25519.decode(Spake2.M_ENCODE))
        assertNotNull(Ed25519.decode(Spake2.N_ENCODE))
    }

    @Test
    fun spake2MatchesReferenceVectors() {
        val pw = password()
        val alice = Spake2(Spake2.Role.ALICE, clientName, serverName)
        val bob = Spake2(Spake2.Role.BOB, serverName, clientName)

        val aliceMsg = alice.generateMsg(pw, seedA())
        val bobMsg = bob.generateMsg(pw, seedB())

        assertEquals(
            "89ac59dbf47d6de1f0e78febcba8174f2230c234729fc4ae2c39c80446601bb6",
            bytesToHex(aliceMsg)
        )
        assertEquals(
            "d04b7071b1354929a32cf497ea43a4d401fb1c3d68084ace4f56a7c9c168d179",
            bytesToHex(bobMsg)
        )

        val keyAlice = alice.processMsg(bobMsg)
        val keyBob = bob.processMsg(aliceMsg)
        assertArrayEquals(keyAlice, keyBob)
        assertEquals(
            "bd34717758dcfea2bc56c4b4535671337044527e61e5ee8f05a2977f7c89e611b" +
                "0e8319ceb6a687bd716b814a1c0c7fd052f5fed40148a835147fad4ea29e2e9",
            bytesToHex(keyAlice)
        )
    }

    @Test
    fun spake2WrongPasswordDiverges() {
        val pw = password()
        val alice = Spake2(Spake2.Role.ALICE, clientName, serverName)
        val bob = Spake2(Spake2.Role.BOB, serverName, clientName)
        val aliceMsg = alice.generateMsg(pw, seedA())
        val bobMsg = bob.generateMsg("654321".toByteArray(Charsets.US_ASCII), seedB())
        assertFalse(Arrays.equals(alice.processMsg(bobMsg), bob.processMsg(aliceMsg)))
    }

    @Test
    fun hkdfRfc5869Vector() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val okm = PairingCrypto.hkdf(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            bytesToHex(okm)
        )
    }

    @Test
    fun aesGcmSequenceRoundTrip() {
        val keyMaterial = ByteArray(64) { (it * 3 + 7).toByte() }
        val sender = PairingCrypto(keyMaterial)
        val receiver = PairingCrypto(keyMaterial)
        val plaintext = ByteArray(8192) { (it % 251).toByte() }

        val ct1 = sender.encrypt(plaintext)
        val ct2 = sender.encrypt(plaintext)
        assertEquals(plaintext.size + 16, ct1.size)
        assertTrue(Arrays.equals(plaintext, receiver.decrypt(ct1)))
        assertTrue(Arrays.equals(plaintext, receiver.decrypt(ct2)))

        val tampered = Arrays.copyOf(ct1, ct1.size)
        tampered[10] = (tampered[10].toInt() xor 0x01).toByte()
        assertTrue(receiver.decrypt(tampered) == null)
    }

    @Test
    fun scReduceMatchesOrder() {
        // x25519_sc_reduce of a 64-byte zero array == 0
        assertTrue(Ed25519.fromLe(Ed25519.scReduce(ByteArray(64))).signum() == 0)
        // L reduces to 0
        val lLe = Ed25519.toLe(Ed25519.L, 64)
        assertTrue(Ed25519.fromLe(Ed25519.scReduce(lLe)).signum() == 0)
    }
}
