package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SettlementManagerTest {

    @Test
    fun parsesBatchDetailInfoObject() {
        val raw = """{"TransResult":true,"BatchDetailInfo":{"BatchId":"10","CreditTotalCount":"3","CreditTotalAmount":"1500","DebitTotalCount":"1","DebitTotalAmount":"400","SettleStatus":0}}"""
        val t = SettlementManager.parseTotals(raw)!!
        assertEquals("10", t.batchId)
        assertEquals("3", t.creditCount)
        assertEquals("1500", t.creditAmount)
        assertEquals("1", t.debitCount)
        assertEquals("400", t.debitAmount)
        assertEquals("0", t.settleStatus)
    }

    @Test
    fun parsesRealPayWizardSettleResponse_withNestedSummaryAndNumbers() {
        // Captured from the Q3mini's PAYWizard emulator, 2026-09-25 (trimmed).
        val raw = """{"AuthCode":"","BatchDetailInfo":{"SettlementList":[],"Summary":{"BatchId":"11","CreditTotalAmount":0,"CreditTotalCount":0,"CurrencyCode":"840","DebitTotalAmount":0,"DebitTotalCount":0,"Mid":"Test  Demo  MID","SettleStatus":"CLOSED","Tid":"Test Demo"},"creditAmount":0,"creditNum":0,"debitAmount":0,"debitNum":0},"TransResult":true}"""
        val t = SettlementManager.parseTotals(raw)!!
        assertEquals("11", t.batchId)
        assertEquals("0", t.creditCount)
        assertEquals("0", t.creditAmount)
        assertEquals("0", t.debitCount)
        assertEquals("CLOSED", t.settleStatus)
    }

    @Test
    fun parsesBatchDetailInfoSentAsString() {
        val raw = """{"BatchDetailInfo":"{\"BatchId\":\"11\",\"CreditTotalCount\":\"0\"}"}"""
        assertEquals("11", SettlementManager.parseTotals(raw)!!.batchId)
    }

    @Test
    fun missingOrBrokenResponseGivesNoTotals() {
        assertNull(SettlementManager.parseTotals(null))
        assertNull(SettlementManager.parseTotals("""{"TransResult":false}"""))
        assertNull(SettlementManager.parseTotals("not json"))
    }

    @Test
    fun nextSettleTimeIsTodayOrTomorrowAt0330() {
        val tz = TimeZone.getTimeZone("America/Toronto")
        fun at(h: Int, m: Int) = Calendar.getInstance(tz).apply { set(2026, Calendar.SEPTEMBER, 24, h, m, 0); set(Calendar.MILLISECOND, 0) }
        // 01:00 -> 2h30m later today
        assertEquals((2 * 60 + 30) * 60_000L, AdManager.millisUntilNext(3, 30, at(1, 0)))
        // 22:00 -> 5h30m later, tomorrow
        assertEquals((5 * 60 + 30) * 60_000L, AdManager.millisUntilNext(3, 30, at(22, 0)))
        // exactly 03:30 -> a full day, never 0
        assertEquals(24 * 60 * 60_000L, AdManager.millisUntilNext(3, 30, at(3, 30)))
    }
}
