package com.goldsky.ssp.iris.hardware.pax

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.goldsky.ssp.payment.hardware.IScannerProvider
import com.pax.dal.IDAL
import com.pax.dal.IScanner

/**
 * PAX implementation of IScannerProvider.
 */
class PaxScannerProvider(
    private val context: Context,
    private val dalProvider: () -> IDAL?
) : IScannerProvider {
    private val TAG = "PaxScanner"
    private var scanner: IScanner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getScanner(): IScanner? {
        if (scanner == null) {
            try {
                scanner = dalProvider()?.scanner
            } catch (e: Exception) {
                Log.e(TAG, "Failed to access PAX scanner: ${e.message}")
            }
        }
        return scanner
    }

    override fun startScan(callback: IScannerProvider.ScanCallback) {
        val s = getScanner()
        if (s == null) {
            startMockScan(callback)
            return
        }

        try {
            s.open()
            s.startScan(30, object : IScanner.ScanListener {
                override fun onSuccess(barcode: String?) {
                    Log.i(TAG, "PAX Scan Success: $barcode")
                    s.close()
                    mainHandler.post { callback.onScanSuccess(barcode ?: "") }
                }

                override fun onFail() {
                    Log.w(TAG, "PAX Scan failed or timed out")
                    s.close()
                    mainHandler.post { callback.onScanFailure("Scanner Timeout") }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error during PAX scan: ${e.message}")
            try {
                s.close()
            } catch (closeError: Exception) {
                Log.e(TAG, "Failed to close scanner after error: ${closeError.message}")
            }
            callback.onScanFailure(e.message ?: "Scanner Error")
        }
    }

    private fun startMockScan(callback: IScannerProvider.ScanCallback) {
        Log.d(TAG, "PAX scanner unavailable: simulating a scan in 4 seconds")
        mainHandler.postDelayed({
            callback.onScanSuccess("MBRQR6789ABC")
        }, 4000)
    }

    override fun stopScan() {
        try {
            scanner?.stopScan()
            scanner?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scanner: ${e.message}")
        }
        scanner = null
    }

    override fun setScannerLed(enabled: Boolean) {
        try {
            getScanner()?.setLed(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set scanner LED: ${e.message}")
        }
    }
}
