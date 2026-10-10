package com.goldsky.ssp.payment

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256 for staff PINs, matching CMP's WebCrypto hashing
 * (docs/migrations/2026-10-10_staff.sql). Hand-rolled over HmacSHA256
 * because SecretKeyFactory only offers PBKDF2WithHmacSHA256 from API 26 and
 * minSdk is 25.
 */
object PinHasher {

    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val out = ByteArray(keyLength)
        var block = 1
        var offset = 0
        while (offset < keyLength) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(t.size, keyLength - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        return out
    }

    fun matches(pin: String, saltHex: String, hashHex: String, iterations: Int): Boolean {
        val expected = hashHex.hexToBytes() ?: return false
        val salt = saltHex.hexToBytes() ?: return false
        val actual = pbkdf2Sha256(pin.toByteArray(Charsets.UTF_8), salt, iterations, expected.size)
        return java.security.MessageDigest.isEqual(actual, expected)
    }

    internal fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
