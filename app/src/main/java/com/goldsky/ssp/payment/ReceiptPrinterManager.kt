package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IPrinterProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-level manager for printing receipts.
 * Dispatches to the appropriate hardware provider based on config.
 */
object ReceiptPrinterManager {
    private const val TAG = "ReceiptPrinterManager"

    data class ReceiptData(
        val brandName: String,
        val amountCents: Int,
        val refNum: String,
        val deviceSn: String
    )

    /**
     * Prints a receipt using the current hardware vendor.
     */
    fun printReceipt(context: Context, data: ReceiptData, vendor: String = "PAX"): Boolean {
        val printer = HardwareFactory.getPrinterProvider(context, vendor)
        
        return try {
            if (!printer.init()) {
                Log.e(TAG, "Printer init failed")
                return false
            }
            
            val lines = buildReceiptLines(data)
            lines.forEach { printer.addText(it) }
            
            printer.startPrint()
            Log.i(TAG, "Receipt printed for ${data.refNum} via $vendor")
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
