package ai.droidpilot.app.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Arrays

/**
 * Minimal edwards25519 group arithmetic (BigInteger, extended coordinates, a = -1).
 *
 * Pure JVM (no android.* imports) so it can be unit-tested on the JVM.
 * Formulas follow RFC 8032 §5.1.4 (add-2008-hwcd-3, unified for add + double).
 * Verified against the RFC 8032 Ed25519 test vector and a Python reference
 * implementation of boringssl's spake25519.
 */
internal object Ed25519 {

    val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")
    val D: BigInteger =
        BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)

    /** Extended point (X:Y:Z:T), x = X/Z, y = Y/Z, T = XY/Z. */
    class Pt(val X: BigInteger, val Y: BigInteger, val Z: BigInteger, val T: BigInteger)

    val IDENTITY: Pt = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** Base point, y = 4/5 mod p, x even (RFC 8032). */
    val BASE: Pt =
        decodeOrThrow(
            hexToBytes("5866666666666666666666666666666666666666666666666666666666666666")
        )

    fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = s.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        }
        return out
    }

    /** Read a little-endian unsigned integer. */
    fun fromLe(b: ByteArray): BigInteger {
        val rev = ByteArray(b.size)
        for (i in b.indices) rev[i] = b[b.size - 1 - i]
        return BigInteger(1, rev)
    }

    /** Write a little-endian integer into exactly [len] bytes (truncates high bytes). */
    fun toLe(v: BigInteger, len: Int): ByteArray {
        val tmp = v.toByteArray()
        val le = ByteArray(len)
        var k = 0
        var i = tmp.size - 1
        while (i >= 0 && k < len) {
            le[k] = tmp[i]
            k++
            i--
        }
        return le
    }

    /** Unified addition (valid for doubling too). */
    fun add(p1: Pt, p2: Pt): Pt {
        val A = p1.Y.subtract(p1.X).multiply(p2.Y.subtract(p2.X)).mod(P)
        val B = p1.Y.add(p1.X).multiply(p2.Y.add(p2.X)).mod(P)
        val C = p1.T.multiply(D).multiply(BigInteger.TWO).multiply(p2.T).mod(P)
        val Dd = p1.Z.multiply(BigInteger.TWO).multiply(p2.Z).mod(P)
        val E = B.subtract(A).mod(P)
        val F = Dd.subtract(C).mod(P)
        val G = Dd.add(C).mod(P)
        val H = B.add(A).mod(P)
        return Pt(E.multiply(F).mod(P), G.multiply(H).mod(P), F.multiply(G).mod(P), E.multiply(H).mod(P))
    }

    fun negate(p: Pt): Pt = Pt(p.X.negate().mod(P), p.Y, p.Z, p.T.negate().mod(P))

    fun sub(a: Pt, b: Pt): Pt = add(a, negate(b))

    /**
     * Double-and-add with the scalar taken as a raw 256-bit little-endian integer,
     * exactly like boringssl's x25519_ge_scalarmult (no mod-L reduction).
     */
    fun scalarMult(scalarLe: ByteArray, p: Pt): Pt {
        val k = fromLe(scalarLe)
        if (k.signum() == 0) return IDENTITY
        var r = p
        var i = k.bitLength() - 2
        while (i >= 0) {
            r = add(r, r)
            if (k.testBit(i)) r = add(r, p)
            i--
        }
        return r
    }

    fun scalarMult(k: BigInteger, p: Pt): Pt = scalarMult(toLe(k, 32), p)

    /** RFC 8032 §5.1.3 decoding; returns null when the point is not on the curve. */
    fun decode(s32: ByteArray): Pt? {
        val copy = Arrays.copyOf(s32, 32)
        val sign = (copy[31].toInt() shr 7) and 1
        copy[31] = (copy[31].toInt() and 0x7f).toByte()
        val y = fromLe(copy)
        if (y >= P) return null
        val u = y.multiply(y).subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y).multiply(y).add(BigInteger.ONE).mod(P)
        val x = recoverX(u, v, sign) ?: return null
        val X = x
        val Y = y
        val Z = BigInteger.ONE
        val T = x.multiply(y).mod(P)
        // on-curve check: -x² + y² == 1 + d·x²·y²
        val lhs = X.multiply(X).negate().add(Y.multiply(Y)).mod(P)
        val rhs = BigInteger.ONE
            .add(D.multiply(X).multiply(X).mod(P).multiply(Y).multiply(Y)).mod(P)
        if (lhs.compareTo(rhs) != 0) return null
        return Pt(X, Y, Z, T)
    }

    private var sqrtM1: BigInteger? = null

    private fun recoverX(u: BigInteger, v: BigInteger, sign: Int): BigInteger? {
        val v3 = v.multiply(v).multiply(v).mod(P)
        val exp = P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8))
        var x = u.multiply(v3).mod(P).modPow(exp, P)
        val vx2 = v.multiply(x).multiply(x).mod(P)
        if (vx2.subtract(u).mod(P).signum() != 0) {
            if (vx2.add(u).mod(P).signum() == 0) {
                var m1 = sqrtM1
                if (m1 == null) {
                    m1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
                    sqrtM1 = m1
                }
                x = x.multiply(m1).mod(P)
            } else {
                return null
            }
        }
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x).mod(P)
        return x
    }

    fun decodeOrThrow(s: ByteArray): Pt {
        return decode(s) ?: throw IllegalArgumentException("point not on curve")
    }

    fun encode(p: Pt): ByteArray {
        val zinv = p.Z.modInverse(P)
        val x = p.X.multiply(zinv).mod(P)
        val y = p.Y.multiply(zinv).mod(P)
        val out = toLe(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** x25519_sc_reduce equivalent: reduce a 64-byte little-endian value mod L. */
    fun scReduce(le64: ByteArray): ByteArray = toLe(fromLe(le64).mod(L), 32)

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (part in parts) md.update(part)
        return md.digest()
    }
}
