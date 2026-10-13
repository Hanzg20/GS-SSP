package com.goldsky.ssp.payment

import android.util.Log
import com.goldsky.ssp.model.KitchenTicket
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The kitchen's own printer on the store network: ESC/POS over TCP 9100, the
 * way Epson TM (and compatible) kitchen printers are run in most restaurants.
 * Chinese in GBK with the printer's Chinese mode on (FS &), which the Chinese
 * Epson models and the common ESC/POS clones understand.
 *
 * Set per terminal in Settings > Kitchen printer; with none set, kitchen
 * tickets print on the terminal's own printer as before.
 */
object NetworkKitchenPrinter {
    private const val TAG = "KitchenPrinter"
    const val DEFAULT_PORT = 9100
    private const val WIDTH = 48          // 80 mm, font A
    private const val CONNECT_MS = 3000
    private val GBK: Charset = Charset.forName("GBK")

    /** One printed line: [big] = double width and height, [tall] = double height only. */
    data class Line(val text: String, val big: Boolean = false, val tall: Boolean = false, val bold: Boolean = false, val center: Boolean = false)

    fun ticketLines(t: KitchenTicket, now: Date = Date()): List<Line> {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(now)
        val rule = Line("-".repeat(WIDTH))
        val out = mutableListOf<Line>()
        if (t.isDineIn) {
            out += Line(if (t.round > 1) "加菜 ADD  (第${t.round}轮)" else "厨房单 KITCHEN", big = true, bold = true, center = true)
            out += Line("桌 ${t.table}", big = true, bold = true, center = true)
            out += Line(listOfNotNull(t.guests?.let { "${it}位" }, "单号 ${t.number}").joinToString("  ·  "), center = true)
        } else {
            out += Line("厨房单 KITCHEN", big = true, bold = true, center = true)
            out += Line("#%03d".format(t.number), big = true, bold = true, center = true)
            out += Line(if (t.takeout) "外带  TAKE-OUT" else "堂食  DINE-IN", tall = true, bold = true, center = true)
        }
        val staff = t.staffName ?: ""
        out += Line(time + " ".repeat((WIDTH - time.length - staff.length).coerceAtLeast(1)) + staff)
        out += rule
        t.lines.forEach { l ->
            out += Line("${l.quantity} x ${l.name}", tall = true, bold = true)
            l.options.forEach { out += Line("    + $it", tall = true) }
            l.note?.takeIf { it.isNotBlank() }?.let { out += Line("    * $it", tall = true) }
        }
        out += rule
        if (t.note.isNotBlank()) {
            out += Line("备注 NOTE: ${t.note.trim()}", tall = true, bold = true)
            out += rule
        }
        return out
    }

    /** The ESC/POS bytes for [lines], ending with a feed and a cut. */
    fun encode(lines: List<Line>): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(byteArrayOf(0x1B, 0x40))            // ESC @  init
        b.write(byteArrayOf(0x1C, 0x26))            // FS &   Chinese mode
        for (l in lines) {
            b.write(byteArrayOf(0x1B, 0x61, if (l.center) 1 else 0))                                       // ESC a  align
            b.write(byteArrayOf(0x1D, 0x21, (if (l.big) 0x11 else if (l.tall) 0x01 else 0x00).toByte()))  // GS !   size
            b.write(byteArrayOf(0x1B, 0x45, if (l.bold) 1 else 0))                                         // ESC E  bold
            b.write(l.text.toByteArray(GBK))
            b.write(0x0A)
        }
        b.write(byteArrayOf(0x1D, 0x21, 0x00, 0x1B, 0x45, 0x00, 0x1B, 0x61, 0x00))
        b.write(byteArrayOf(0x1B, 0x64, 4))         // ESC d 4: feed past the cutter
        b.write(byteArrayOf(0x1D, 0x56, 66, 0))     // GS V B 0: partial cut
        return b.toByteArray()
    }

    /** Sends [lines] to host:port; false when the printer can't be reached. */
    fun print(host: String, port: Int, lines: List<Line>): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), CONNECT_MS)
            s.soTimeout = CONNECT_MS
            s.getOutputStream().apply { write(encode(lines)); flush() }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Kitchen printer $host:$port failed: ${e.message}")
        false
    }

    fun printTicket(host: String, port: Int, t: KitchenTicket): Boolean = print(host, port, ticketLines(t))

    fun testLines(): List<Line> = listOf(
        Line("厨房打印机测试", big = true, bold = true, center = true),
        Line("KITCHEN PRINTER TEST", bold = true, center = true),
        Line(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()), center = true),
        Line("-".repeat(WIDTH)),
        Line("2 x 牛肉面 Beef Noodle Soup", tall = true, bold = true),
        Line("    + 微辣 Mild", tall = true),
        Line("-".repeat(WIDTH))
    )
}
