package com.goldsky.ssp.payment.hardware.idtech

import com.idtechproducts.device.IDTEMVData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in the code-to-category mapping derived from the vendored SDK's own
 * named constants (see EmvResultClassifier.kt) -- this is the part most
 * likely to silently regress if anyone "simplifies" it back to guessed
 * magic numbers, since a wrong mapping here either fails a good card
 * (customer-facing annoyance) or, worse, treats a non-final/declined code
 * as APPROVED (money-safety bug).
 */
class EmvResultClassifierTest {

    @Test
    fun `kernel handshake success is CONTINUE, not a final outcome`() {
        assertEquals(EmvResultCategory.CONTINUE, classifyEmvResult(IDTEMVData.START_TRANS_SUCCESS))
    }

    @Test
    fun `GO_ONLINE and GO_ONLINE_CTLS both require online auth we don't have`() {
        assertEquals(EmvResultCategory.ONLINE_AUTH_REQUIRED, classifyEmvResult(IDTEMVData.GO_ONLINE))
        assertEquals(EmvResultCategory.ONLINE_AUTH_REQUIRED, classifyEmvResult(IDTEMVData.GO_ONLINE_CTLS))
    }

    @Test
    fun `APPROVED and APPROVED_OFFLINE are the only APPROVED codes`() {
        assertEquals(EmvResultCategory.APPROVED, classifyEmvResult(IDTEMVData.APPROVED))
        assertEquals(EmvResultCategory.APPROVED, classifyEmvResult(IDTEMVData.APPROVED_OFFLINE))
    }

    @Test
    fun `decline-family codes all map to DECLINED`() {
        val declineCodes = listOf(
            IDTEMVData.DECLINED,
            IDTEMVData.DECLINED_OFFLINE,
            IDTEMVData.NOT_ACCEPTED,
            IDTEMVData.CARD_REJECTED,
            IDTEMVData.CARD_BLOCKED,
            IDTEMVData.CALL_YOUR_BANK,
        )
        declineCodes.forEach { code ->
            assertEquals("code $code should be DECLINED", EmvResultCategory.DECLINED, classifyEmvResult(code))
        }
    }

    @Test
    fun `cancellation maps to CANCELLED`() {
        assertEquals(EmvResultCategory.CANCELLED, classifyEmvResult(IDTEMVData.TRANSACTION_CANCELED))
    }

    @Test
    fun `timeouts map to TIMEOUT`() {
        assertEquals(EmvResultCategory.TIMEOUT, classifyEmvResult(IDTEMVData.TIME_OUT))
        assertEquals(EmvResultCategory.TIMEOUT, classifyEmvResult(IDTEMVData.PIN_ENTRY_TIMEOUT))
    }

    @Test
    fun `chip-read-failed codes map to FALLBACK`() {
        val fallbackCodes = listOf(
            IDTEMVData.USE_MAGSTRIPE,
            IDTEMVData.FALLBACK_TO_CONTACT,
            IDTEMVData.FALLBACK_TO_OTHER,
            IDTEMVData.FALLBACK_SITUATION,
        )
        fallbackCodes.forEach { code ->
            assertEquals("code $code should be FALLBACK", EmvResultCategory.FALLBACK, classifyEmvResult(code))
        }
    }

    @Test
    fun `CTLS retry codes map to CTLS_RETRY`() {
        val ctlsRetryCodes = listOf(
            IDTEMVData.CTLS_TWO_CARDS,
            IDTEMVData.CTLS_TERMINATE,
            IDTEMVData.CTLS_TERMINATE_TRY_ANOTHER,
        )
        ctlsRetryCodes.forEach { code ->
            assertEquals("code $code should be CTLS_RETRY", EmvResultCategory.CTLS_RETRY, classifyEmvResult(code))
        }
    }

    @Test
    fun `signature and online-PIN requests map to VERIFICATION_UNSUPPORTED`() {
        assertEquals(EmvResultCategory.VERIFICATION_UNSUPPORTED, classifyEmvResult(IDTEMVData.REQUEST_SIGNATURE))
        assertEquals(EmvResultCategory.VERIFICATION_UNSUPPORTED, classifyEmvResult(IDTEMVData.REQUEST_ONLINE_PIN))
    }

    @Test
    fun `an unrecognized code falls back to UNKNOWN, never APPROVED`() {
        val bogusCode = -999999
        assertEquals(EmvResultCategory.UNKNOWN, classifyEmvResult(bogusCode))
    }

    @Test
    fun `every named IDTEMVData result constant is mapped to something other than UNKNOWN`() {
        // Guards against a future SDK constant being added/renamed and silently
        // falling through to UNKNOWN instead of getting a deliberate category.
        val allNamedResultCodes = listOf(
            IDTEMVData.START_TRANS_SUCCESS, IDTEMVData.GO_ONLINE, IDTEMVData.GO_ONLINE_CTLS,
            IDTEMVData.APPROVED, IDTEMVData.APPROVED_OFFLINE,
            IDTEMVData.DECLINED, IDTEMVData.DECLINED_OFFLINE, IDTEMVData.NOT_ACCEPTED,
            IDTEMVData.CARD_REJECTED, IDTEMVData.CARD_BLOCKED, IDTEMVData.CALL_YOUR_BANK,
            IDTEMVData.TRANSACTION_CANCELED, IDTEMVData.TIME_OUT, IDTEMVData.PIN_ENTRY_TIMEOUT,
            IDTEMVData.USE_MAGSTRIPE, IDTEMVData.FALLBACK_TO_CONTACT, IDTEMVData.FALLBACK_TO_OTHER,
            IDTEMVData.FALLBACK_SITUATION, IDTEMVData.CTLS_TWO_CARDS, IDTEMVData.CTLS_TERMINATE,
            IDTEMVData.CTLS_TERMINATE_TRY_ANOTHER, IDTEMVData.REQUEST_SIGNATURE, IDTEMVData.REQUEST_ONLINE_PIN,
        )
        allNamedResultCodes.forEach { code ->
            assertEquals(
                "code $code unexpectedly fell through to UNKNOWN",
                false,
                classifyEmvResult(code) == EmvResultCategory.UNKNOWN,
            )
        }
    }
}
