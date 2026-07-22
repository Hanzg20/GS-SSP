package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import com.pax.dal.IPrinter
import com.pax.neptunelite.api.NeptuneLiteUser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wraps the PAX thermal printer for optional receipt printing. Whether to
 * print at all is driven by KioskSettings.print_receipt_enabled (cloud
 * config) -- not hardcoded, since some deployments run without paper loaded
 * or deliberately skip receipts. Falls back to a logged mock print when the
 * NeptuneLite SDK / real printer hardware isn't present (emulator, dev),
 * same pattern as PaxScannerManager.
 */
object ReceiptPrinterManager {
    private const val TAG = "ReceiptPrinterManager"

    /**
     * A class being loadable is not proof real PAX hardware is present: the
     * local compile-time stubs are always on the classpath. Requires context
     * so it can't be lazily precomputed without one -- see printReceipt.
     */
    private fun checkPaxAvailability(context: Context): Boolean {
        return try {
            Class.forName("com.pax.neptunelite.api.NeptuneLiteUser")
            NeptuneLiteUser.getInstance().getDal(context) != null
        } catch (e: Exception) {
            Log.w(TAG, "NeptuneLite SDK/hardware not available. Receipt printing will be mocked: ${e.message}")
            false
        }
    }

    data class ReceiptData(
        val brandName: String,
        val amountCents: Int,
        val refNum: String,
        val deviceSn: String
    )

    /**
     * Prints a receipt if hardware/mock is reachable. Never throws -- a
     * failed or skipped receipt must not block the payment flow, so callers
     * should treat the return value as informational, not gate success on it.
     */
    fun printReceipt(context: Context, data: ReceiptData): Boolean {
        val lines = buildReceiptLines(data)
        if (!checkPaxAvailability(context)) {
            Log.i(TAG, "MOCK PRINT (no PAX SDK):\n${lines.joinToString("\n")}")
            return true
        }
        return try {
            val dal = NeptuneLiteUser.getInstance().getDal(context)
            val printer: IPrinter = dal.printer
            printer.init()
            lines.forEach { printer.addText(it) }
            printer.step()
            Log.i(TAG, "Receipt printed for ${data.refNum}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Receipt print failed: ${e.message}")
            false
        }
    }

    private fun buildReceiptLines(data: ReceiptData): List<String> {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return listOf(
            data.brandName,
            "--------------------------------",
            "Date: $timestamp",
            "Device: ${data.deviceSn}",
            "Ref#: ${data.refNum}",
            "Amount: $${"%.2f".format(data.amountCents / 100.0)}",
            "--------------------------------",
            "Thank you for your business!"
        )
    }
}
