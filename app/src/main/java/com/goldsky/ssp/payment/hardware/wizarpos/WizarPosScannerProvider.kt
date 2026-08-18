package com.goldsky.ssp.payment.hardware.wizarpos

import android.content.Context
import android.util.Log
import com.cloudpos.advance.ext.POSTerminalAdvance
import com.cloudpos.advance.ext.scanner.IScanCallBack
import com.cloudpos.advance.ext.scanner.IScannerDevice
import com.cloudpos.advance.ext.scanner.ScanParameter
import com.cloudpos.advance.ext.scanner.ScanResult
import com.goldsky.ssp.payment.hardware.IScannerProvider

/**
 * WizarPOS Scanner implementation using CloudPOS Advance SDK.
 */
class WizarPosScannerProvider(private val context: Context) : IScannerProvider {
    
    companion object {
        private const val TAG = "WizarPosScanner"
    }
    
    private var scannerDevice: IScannerDevice? = null

    private fun ensureOpened(): Boolean {
        if (scannerDevice == null) {
            try {
                scannerDevice = POSTerminalAdvance.getInstance().getScannerDevice()
                scannerDevice?.open(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open WizarPOS scanner: ${e.message}")
                return false
            }
        }
        return scannerDevice?.isOpened ?: false
    }

    override fun startScan(callback: IScannerProvider.ScanCallback) {
        if (!ensureOpened()) {
            callback.onScanFailure("Hardware initialization failed")
            return
        }

        try {
            val param = ScanParameter() 
            scannerDevice?.startScan(param, object : IScanCallBack {
                override fun foundBarcode(result: ScanResult) {
                    if (result.resultCode == ScanResult.SCAN_SUCCESS) {
                        callback.onScanSuccess(result.text ?: "")
                    } else {
                        callback.onScanFailure("Scan error: ${result.resultCode}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Start scan error: ${e.message}")
            callback.onScanFailure("Internal error: ${e.message}")
        }
    }

    override fun stopScan() {
        try {
            scannerDevice?.stopScan()
            scannerDevice?.close()
            scannerDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Stop scan error: ${e.message}")
        }
    }

    override fun setScannerLed(enabled: Boolean) {
    }
}
