package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `matches the RFC 7914 PBKDF2-HMAC-SHA256 vectors`() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(PinHasher.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64))
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            hex(PinHasher.pbkdf2Sha256("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64))
        )
    }

    @Test
    fun `a stored PIN verifies and a wrong one does not`() {
        val salt = "00112233445566778899aabbccddeeff"
        val hash = hex(PinHasher.pbkdf2Sha256("4821".toByteArray(), ByteArray(16) { (it * 0x11).toByte() }, 20000))
        assertTrue(PinHasher.matches("4821", salt, hash, 20000))
        assertFalse(PinHasher.matches("4822", salt, hash, 20000))
        assertFalse(PinHasher.matches("4821", "zz", hash, 20000))
    }
}
