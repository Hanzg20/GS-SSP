package com.goldsky.ssp.payment

import com.goldsky.ssp.model.KitchenLine
import com.goldsky.ssp.model.KitchenTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KitchenTicketTest {

    private val noodles = KitchenLine("牛肉面 Beef Noodle Soup", 2, listOf("微辣 Mild", "加蛋 Egg"))
    private val dumplings = KitchenLine("煎饺 Pan-fried Dumplings (8)", 1, emptyList())

    @Test
    fun `number, type, dishes with options, and note`() {
        val out = ReceiptPrinterManager.kitchenTicketLines(KitchenTicket(7, takeout = true, note = "走葱", lines = listOf(noodles, dumplings), staffName = "Amy"))
        assertTrue(out.contains("      ##  007  ##"))
        assertTrue(out.contains("    外带  TAKE-OUT"))
        val dish = out.indexOf("2 x 牛肉面 Beef Noodle Soup")
        assertEquals("    + 微辣 Mild", out[dish + 1])
        assertEquals("    + 加蛋 Egg", out[dish + 2])
        assertEquals("1 x 煎饺 Pan-fried Dumplings (8)", out[dish + 3])
        assertEquals("走葱", out[out.indexOf("备注 NOTE:") + 1])
        // No prices on the kitchen's copy.
        assertFalse(out.any { it.contains("$") || it.contains("12.99") })
    }

    @Test
    fun `dine-in without a note has no note block`() {
        val out = ReceiptPrinterManager.kitchenTicketLines(KitchenTicket(123, takeout = false, note = "  ", lines = listOf(dumplings), staffName = null))
        assertTrue(out.contains("    堂食  DINE-IN"))
        assertTrue(out.contains("      ##  123  ##"))
        assertFalse(out.any { it.startsWith("备注") })
    }

    @Test
    fun `receipt banner and footer`() {
        val t = KitchenTicket(42, takeout = false, note = "少冰", lines = emptyList(), staffName = null)
        assertEquals("ORDER #042  DINE-IN 堂食", ReceiptPrinterManager.orderBanner(t))
        assertEquals(listOf("Note 备注: 少冰"), ReceiptPrinterManager.orderFooter(t))
        assertEquals(emptyList<String>(), ReceiptPrinterManager.orderFooter(t.copy(note = "")))
    }

    @Test
    fun `Arke online order - pickup number and the customer's name`() {
        val t = KitchenTicket(12, takeout = true, note = "多给餐具", lines = listOf(noodles), staffName = "Amy", onlineCode = "A12", customer = "Lily")
        val out = ReceiptPrinterManager.kitchenTicketLines(t)
        assertTrue(out.contains("    线上自取  ONLINE PICKUP"))
        assertTrue(out.contains("      ##  A12  ##"))
        assertTrue(out.contains("    Lily"))
        assertTrue(out.none { it.contains("TAKE-OUT") })
        val net = NetworkKitchenPrinter.ticketLines(t).map { it.text }
        assertTrue(net.contains("线上自取 ONLINE PICKUP"))
        assertTrue(net.contains("A12"))
        assertTrue(net.contains("Lily"))
    }

    @Test
    fun `Arke delivery order - delivery header and the address for the driver`() {
        val t = KitchenTicket(7, takeout = true, note = "", lines = listOf(noodles), staffName = "Amy", onlineCode = "D7", customer = "Lily",
            deliveryTo = listOf("123 Main St, 1203", "Ottawa K2K 1X1", "Tel 6135550100"))
        val out = ReceiptPrinterManager.kitchenTicketLines(t)
        assertTrue(out.contains("    线上配送  DELIVERY"))
        assertTrue(out.contains("      ##  D7  ##"))
        assertTrue(out.contains("    123 Main St, 1203"))
        assertTrue(out.contains("    Tel 6135550100"))
        assertTrue(out.none { it.contains("PICKUP") })
        val net = NetworkKitchenPrinter.ticketLines(t).map { it.text }
        assertTrue(net.contains("线上配送 DELIVERY"))
        assertTrue(net.contains("Ottawa K2K 1X1"))
    }
}
