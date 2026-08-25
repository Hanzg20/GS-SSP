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
        val deviceSn: String
    )

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
