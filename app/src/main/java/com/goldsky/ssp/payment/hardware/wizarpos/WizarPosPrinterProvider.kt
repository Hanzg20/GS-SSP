package com.goldsky.ssp.payment.hardware.wizarpos

import android.util.Log
import com.cloudpos.POSTerminal
import com.cloudpos.printer.PrinterDevice
import com.goldsky.ssp.payment.hardware.IPrinterProvider

/**
 * WizarPOS implementation of IPrinterProvider using CloudPOS SDK.
 */
class WizarPosPrinterProvider(private val terminal: POSTerminal?) : IPrinterProvider {
    
    private val TAG = "WizarPosPrinter"
    private var printerDevice: PrinterDevice? = null

    private fun ensureOpened(): Boolean {
        if (printerDevice == null) {
            try {
                printerDevice = terminal?.getDevice("com.cloudpos.device.printer") as? PrinterDevice
                printerDevice?.open()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open WizarPOS printer: ${e.message}")
                return false
            }
        }
        return printerDevice != null
    }

    override fun init(): Boolean {
        return try {
            if (ensureOpened()) {
                // In CloudPOS, opening it is often enough to init, 
                // but some SDK versions have explicit init.
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    override fun addText(text: String): Boolean {
        return try {
            if (ensureOpened()) {
                printerDevice?.printText(text + "\n")
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Print text failed: ${e.message}")
            false
        }
    }

    override fun startPrint(): Boolean {
        // CloudPOS printer calls like printText are often synchronous or 
        // buffer until a step/feed command.
        return true 
    }

    override fun feedPaper(lines: Int): Boolean {
        return try {
            if (ensureOpened()) {
                printerDevice?.cutPaper() // Or manual feed if available
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    override fun hasPaper(): Boolean {
        return try {
            // Check status via query if available
            true 
        } catch (e: Exception) {
            false
        }
    }
}
