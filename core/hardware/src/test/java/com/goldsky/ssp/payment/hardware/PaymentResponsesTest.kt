package com.goldsky.ssp.payment.hardware

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentResponsesTest {

    @Test
    fun `card number masked, secrets dropped, the rest kept`() {
        val clean = PaymentResponses.sanitize(
            """{"TransResult":true,"CardNum":"5471871234565777","Track2Data":";5471=27","PinBlock":"AB",
               "ExpiryDate":"2704","CVV2":"123","SignatureImage":"xx","RRN":"000073","TransID":"73",
               "HostTransId":"7110000012345"}"""
        )!!
        assertEquals("547187******5777", clean["CardNum"]!!.jsonPrimitive.content)
        listOf("Track2Data", "PinBlock", "ExpiryDate", "CVV2", "SignatureImage").forEach { assertFalse(it, clean.containsKey(it)) }
        assertEquals("000073", clean["RRN"]!!.jsonPrimitive.content)
        assertEquals("7110000012345", clean["HostTransId"]!!.jsonPrimitive.content)
        assertEquals("true", clean["TransResult"]!!.jsonPrimitive.content)
    }

    @Test
    fun `already masked or short numbers stay as they are`() {
        assertEquals("547187******5777", PaymentResponses.maskPan("547187******5777"))
        assertEquals("1234", PaymentResponses.maskPan("1234"))
    }

    @Test
    fun `not a JSON object gives null`() {
        assertNull(PaymentResponses.sanitize("not json"))
        assertNull(PaymentResponses.sanitize("[1,2]"))
    }
}
