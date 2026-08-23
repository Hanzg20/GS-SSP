package com.goldsky.ssp.payment.hardware.pax

import android.util.Log
import com.goldsky.ssp.payment.hardware.IPrinterProvider
import com.pax.dal.IDAL
import com.pax.dal.IPrinter

/**
 * PAX implementation of IPrinterProvider using NeptuneLite SDK.
 */
class PaxPrinterProvider(private val dalProvider: () -> IDAL?) : IPrinterProvider {
    
    private val TAG = "PaxPrinter"
    private var printer: IPrinter? = null

    private fun ensureOpened(): Boolean {
        if (printer == null) {
            printer = dalProvider()?.printer
        }
        return printer != null
    }

    override fun init(): Boolean {
        return try {
            if (ensureOpened()) {
                printer?.init()
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Printer init failed: ${e.message}")
            false
        }
    }

    override fun addText(text: String): Boolean {
        return try {
            printer?.addText(text)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Printer addText failed: ${e.message}")
            false
        }
    }

    override fun startPrint(): Boolean {
        return try {
            // In NeptuneLite, step() triggers the actual print of the buffer
            printer?.step()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Printer start failed: ${e.message}")
            false
        }
    }

    override fun feedPaper(lines: Int): Boolean {
        return try {
            // Repeatedly step to feed
            repeat(lines) { printer?.step() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Printer feedPaper failed: ${e.message}")
            false
        }
    }

    override fun hasPaper(): Boolean {
        return try {
            printer?.status == 0 // 0 means OK/Has Paper
        } catch (e: Exception) {
            false
        }
    }
}
