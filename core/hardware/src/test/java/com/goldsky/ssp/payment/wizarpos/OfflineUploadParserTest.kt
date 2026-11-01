package com.goldsky.ssp.payment.wizarpos

import com.goldsky.ssp.payment.hardware.IPaymentProvider.OfflineUpload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineUploadParserTest {
    @Test fun perSaleResults() {
        val r = OfflineUploadParser.parse("""{"TransType":"Deferred","TransResult":true,"DeferredSummaryInfo":{"SuccessCount":1,"FailCount":1,
            "Details":[{"TransIndexCode":"RET-1","TransResult":"true"},{"TransIndexCode":"RET-2","RespCode":"05","TransResult":false}]}}""") as OfflineUpload.Done
        assertEquals(listOf("RET-1"), r.uploaded)
        assertEquals(listOf("RET-2"), r.declined)
        assertFalse(r.allSent)
    }

    @Test fun countsOnlyAllSent() {
        val r = OfflineUploadParser.parse("""{"TransResult":true,"DeferredSummaryInfo":{"successNum":"3","failNum":"0"}}""") as OfflineUpload.Done
        assertTrue(r.allSent)
        assertTrue(r.uploaded.isEmpty())
    }

    @Test fun noCountsIsNotAllSent() {
        val r = OfflineUploadParser.parse("""{"TransResult":true,"RespCode":"00"}""") as OfflineUpload.Done
        assertFalse(r.allSent)
    }

    @Test fun refusedWithoutDetailIsAFailure() {
        assertTrue(OfflineUploadParser.parse("""{"TransResult":false,"RespDesc":"No network"}""") is OfflineUpload.Failed)
        assertTrue(OfflineUploadParser.parse("not json") is OfflineUpload.Failed)
    }

    @Test fun unknownResultStaysPending() {
        val r = OfflineUploadParser.parse("""{"TransResult":true,"DeferredSummaryInfo":[{"TransIndexCode":"RET-9","Note":"x"}]}""") as OfflineUpload.Done
        assertTrue(r.uploaded.isEmpty() && r.declined.isEmpty())
    }
}
