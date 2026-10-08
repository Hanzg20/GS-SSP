package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentTestSuiteTest {
    private fun case(id: String) = PaymentTestSuite.cases.first { it.id == id }

    @Test fun approvedPurchasePasses() {
        val r = PaymentTestSuite.evaluate(case("PT-01"), mapOf("TransType" to "Purchase"),
            """{"TransResult":true,"RespCode":"00","RespDesc":"Approved","TransID":"17"}""")
        assertEquals(PaymentTestSuite.Status.PASS, r.status)
        assertEquals("17", r.transId)
    }

    @Test fun declineNeedsTheRightReason() {
        val ok = PaymentTestSuite.evaluate(case("PT-17"), emptyMap(), """{"TransResult":false,"RespDesc":"Insufficient Funds"}""")
        val wrong = PaymentTestSuite.evaluate(case("PT-17"), emptyMap(), """{"TransResult":false,"RespDesc":"Declined"}""")
        assertEquals(PaymentTestSuite.Status.PASS, ok.status)
        assertEquals(PaymentTestSuite.Status.FAIL, wrong.status)
    }

    @Test fun chipCaseChecksEntryMode() {
        val chip = PaymentTestSuite.evaluate(case("A-09"), emptyMap(), """{"TransResult":true,"RespDesc":"Approved","EntryMode":5}""")
        val tap = PaymentTestSuite.evaluate(case("A-09"), emptyMap(), """{"TransResult":true,"RespDesc":"Approved","EntryMode":7}""")
        assertEquals(PaymentTestSuite.Status.PASS, chip.status)
        assertEquals(PaymentTestSuite.Status.FAIL, tap.status)
    }

    @Test fun reversalNeedsPt01AndSendsNoAmount() {
        assertNull(case("PT-05").request("R", emptyMap()))
        val pt01 = PaymentTestSuite.Result("PT-01", PaymentTestSuite.Status.PASS, "", mapOf("TransIndexCode" to "X"), """{"TransID":"42"}""")
        val req = case("PT-05").request("R", mapOf("PT-01" to pt01))!!
        assertEquals("42", req["OriTransId"])
        assertEquals(null, req["TransAmount"])
    }

    @Test fun noAnswerIsNoResponse() {
        assertEquals(PaymentTestSuite.Status.NO_RESPONSE, PaymentTestSuite.evaluate(case("PT-22"), emptyMap(), null).status)
    }
}
