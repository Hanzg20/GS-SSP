package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.HardwareFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-level manager for printing professional retail receipts.
 * Dispatches to the appropriate hardware provider based on config.
 */
object ReceiptPrinterManager {
    private const val TAG = "ReceiptPrinterManager"

    data class ReceiptData(
        val subtotalCents: Int? = null,
        val taxCents: Int? = null,
        val tipCents: Int? = null,
        val amountCents: Int,
        val refNum: String,
        val deviceSn: String,
        // Sale lines; empty = totals-only receipt (wash, keypad-less callers).
        val lines: List<com.goldsky.ssp.model.SaleLine> = emptyList(),
        // Printed as a banner under the header, e.g. "*** REFUND ***".
        val banner: String? = null,
        // Extra lines after the totals (e.g. who approved a refund).
        val footer: List<String> = emptyList()
    )

    private const val WIDTH = 32

    private fun money(cents: Int) = "%.2f".format(cents / 100.0)

    /** [left] and [right] on one 32-column line; ASCII only (CJK is double width). */
    internal fun row(left: String, right: String): String =
        left + " ".repeat((WIDTH - left.length - right.length).coerceAtLeast(1)) + right

    private fun rateLabel(bps: Int): String {
        val pct = if (bps % 100 == 0) "${bps / 100}" else "%.2f".format(bps / 100.0).trimEnd('0')
        return if (bps == 1300) "HST 13%" else "TAX $pct%"
    }

    /**
     * Item block + tax breakdown. Name on its own line (it may be CJK, whose
     * width the column math can't know), then "qty x price ... total" with a
     * trailing H on taxed lines -- the usual Canadian receipt marking.
     */
    internal fun itemLines(lines: List<com.goldsky.ssp.model.SaleLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        for (l in lines) {
            out += l.name
            val mark = if (l.taxRateBps > 0) " H" else "  "
            out += row("  ${l.quantity} x ${money(l.unitPriceCents)}", money(l.lineTotalCents) + mark)
        }
        out += "-".repeat(WIDTH)
        val pairs = lines.map { it.lineTotalCents to it.taxRateBps }
        for ((bps, base, tax) in SalesTax.byRate(pairs)) {
            out += row("${rateLabel(bps)} on ${money(base)}", money(tax) + "  ")
        }
        val exempt = lines.filter { it.taxRateBps == 0 }.sumOf { it.lineTotalCents }
        if (exempt > 0) out += row("Tax exempt items", money(exempt) + "  ")
        if (lines.any { it.taxRateBps > 0 }) out += "H = taxable item"
        return out
    }

    /**
     * The kitchen's copy of a quick-service order, Chinese first: call number
     * big and boxed, dine-in / take-out, each dish with its options indented
     * under it, the order note last. No prices -- the kitchen doesn't need them.
     */
    fun printKitchenTicket(context: Context, ticket: com.goldsky.ssp.model.KitchenTicket, vendor: String): Boolean =
        printLines(context, kitchenTicketLines(ticket), vendor)

    internal fun kitchenTicketLines(t: com.goldsky.ssp.model.KitchenTicket): List<String> {
        val out = mutableListOf<String>()
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        out += "=".repeat(WIDTH)
        out += "    厨房单  KITCHEN"
        out += "      ##  %03d  ##".format(t.number)
        out += if (t.takeout) "    外带  TAKE-OUT" else "    堂食  DINE-IN"
        out += "=".repeat(WIDTH)
        out += row(time, t.staffName?.take(14) ?: "")
        out += "-".repeat(WIDTH)
        t.lines.forEach { line ->
            out += "${line.quantity} x ${line.name}"
            line.options.forEach { out += "    + $it" }
        }
        out += "-".repeat(WIDTH)
        if (t.note.isNotBlank()) {
            out += "备注 NOTE:"
            out += t.note.trim()
            out += "-".repeat(WIDTH)
        }
        return out
    }

    /**
     * After a paid sale: on a quick-service counter ([ticket] set) the kitchen
     * ticket first, then the customer's receipt with the call number on top
     * and the note at the bottom; otherwise just the receipt.
     */
    fun printOrder(context: Context, ticket: com.goldsky.ssp.model.KitchenTicket?, receipt: ReceiptData, vendor: String) {
        if (ticket != null) printKitchenTicket(context, ticket, vendor)
        printReceipt(
            context,
            if (ticket == null) receipt
            else receipt.copy(banner = orderBanner(ticket), footer = receipt.footer + orderFooter(ticket)),
            vendor
        )
    }

    /** The order number, type and note at the top/bottom of the customer's receipt. */
    fun orderBanner(t: com.goldsky.ssp.model.KitchenTicket): String =
        "ORDER #%03d  %s".format(t.number, if (t.takeout) "TAKE-OUT 外带" else "DINE-IN 堂食")

    fun orderFooter(t: com.goldsky.ssp.model.KitchenTicket): List<String> =
        if (t.note.isBlank()) emptyList() else listOf("Note 备注: ${t.note.trim()}")

    /** Prints [lines] as they are (reports), then feeds and cuts. */
    fun printLines(context: Context, lines: List<String>, vendor: String): Boolean {
        val printer = HardwareFactory.getPrinterProvider(context, vendor)
        return try {
            if (!printer.init()) return false
            lines.forEach { printer.addText(it) }
            printer.feedPaper(3)
            printer.startPrint()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Report print failed: ${e.message}")
            false
        }
    }

    /**
     * Prints a professional retail receipt with branding and tax breakdown.
     */
    fun printReceipt(context: Context, data: ReceiptData, vendor: String = "PAX"): Boolean {
        val printer = HardwareFactory.getPrinterProvider(context, vendor)
        val storeName = DeviceRepository.getStoreName()
        val storeAddr = DeviceRepository.getStoreAddress()
        val storePhone = DeviceRepository.getStorePhone()
        
        return try {
            if (!printer.init()) {
                Log.e(TAG, "Printer init failed")
                return false
            }
            
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            
            // Premium Template
            printer.addText("      $storeName")
            printer.addText(storeAddr)
            printer.addText("TEL: $storePhone")
            printer.addText("--------------------------------")
            printer.addText("Date: $timestamp")
            printer.addText("Ref: ${data.refNum.takeLast(12)}")
            printer.addText("Device: ${data.deviceSn.takeLast(8)}")
            printer.addText("--------------------------------")
            data.banner?.let { printer.addText(it); printer.addText("--------------------------------") }
            itemLines(data.lines).forEach { printer.addText(it) }
            if (data.lines.isNotEmpty()) printer.addText("--------------------------------")

            val tax = data.taxCents ?: 0
            val tip = data.tipCents ?: 0
            val sub = data.subtotalCents ?: (data.amountCents - tax - tip)

            printer.addText("SUBTOTAL:        $${"%.2f".format(sub / 100.0)}")
            if (tax > 0) {
                printer.addText("TAX:             $${"%.2f".format(tax / 100.0)}")
            }
            if (tip > 0) {
                printer.addText("TIP:             $${"%.2f".format(tip / 100.0)}")
            }
            printer.addText("--------------------------------")
            printer.addText("TOTAL:           $${"%.2f".format(data.amountCents / 100.0)}")
            printer.addText("--------------------------------")
            data.footer.forEach { printer.addText(it) }
            if (data.footer.isNotEmpty()) printer.addText("--------------------------------")
            printer.addText("    THANK YOU FOR SHOPPING!")
            
            printer.feedPaper(3)
            printer.startPrint()
            
            Log.i(TAG, "Premium receipt printed for ${data.refNum}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Receipt print failed: ${e.message}")
            false
        }
    }
}
