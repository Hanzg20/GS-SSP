package com.goldsky.ssp.payment

import com.goldsky.ssp.model.KitchenLine
import com.goldsky.ssp.model.KitchenTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class NetworkKitchenPrinterTest {

    private val ticket = KitchenTicket(12, takeout = false, note = "少辣", staffName = "Amy", table = "T5", round = 2, guests = 4,
        lines = listOf(KitchenLine("牛肉面", 2, listOf("微辣"), note = "面硬一点")))

    @Test
    fun `dine-in add round names the table and round`() {
        val lines = NetworkKitchenPrinter.ticketLines(ticket).map { it.text }
        assertEquals("加菜 ADD  (第2轮)", lines[0])
        assertEquals("桌 T5", lines[1])
        assertTrue(lines.contains("2 x 牛肉面"))
        assertTrue(lines.contains("    + 微辣"))
        assertTrue(lines.contains("    * 面硬一点"))
        assertTrue(lines.contains("备注 NOTE: 少辣"))
    }

    @Test
    fun `bytes are ESC-POS with GBK text and a cut`() {
        val bytes = NetworkKitchenPrinter.encode(listOf(NetworkKitchenPrinter.Line("桌 T5", big = true)))
        assertEquals(listOf<Byte>(0x1B, 0x40, 0x1C, 0x26), bytes.take(4))
        val gbk = "桌 T5".toByteArray(Charset.forName("GBK"))
        assertTrue(bytes.toList().windowed(gbk.size).any { it == gbk.toList() })
        assertTrue(bytes.toList().windowed(3).any { it == listOf<Byte>(0x1D, 0x21, 0x11) })   // double size
        assertEquals(listOf<Byte>(0x1D, 0x56, 66, 0), bytes.takeLast(4))                      // cut
    }
}
