package com.goldsky.carwash.payment.hardware.pax

import android.content.Context
import android.util.Log
import com.goldsky.carwash.payment.hardware.IScannerProvider
import com.pax.dal.IDAL
import com.pax.dal.IScanner
import com.pax.neptunelite.api.NeptuneLiteUser

/**
 * PAX implementation of IScannerProvider.
 * Drives the physical 1D/2D scanner module on the IM30.
 */
class PaxScannerProvider(private val context: Context) : IScannerProvider {
    private val TAG = "PaxScanner"
    private var scanner: IScanner? = null

    private fun getScanner(): IScanner? {
        if (scanner == null) {
            try {
                val dal: IDAL? = NeptuneLiteUser.getInstance().getDal(context)
                scanner = dal?.scanner
            } catch (e: Exception) {
                Log.e(TAG, "Failed to access PAX scanner: ${e.message}")
            }
        }
        return scanner
    }

    override fun startScan(callback: IScannerProvider.ScanCallback) {
        val s = getScanner()
        if (s == null) {
            callback.onScanFailure("Scanner hardware not available")
            return
        }

        try {
            s.open()
            s.startScan(30, object : IScanner.ScanListener {
                override fun onSuccess(barcode: String?) {
                    Log.i(TAG, "PAX Scan Success: $barcode")
                    s.close()
                    callback.onScanSuccess(barcode ?: "")
                }

                override fun onFail() {
                    Log.w(TAG, "PAX Scan failed or timed out")
                    s.close()
                    callback.onScanFailure("Scanner Timeout")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error during PAX scan: ${e.message}")
            callback.onScanFailure(e.message ?: "Scanner Error")
        }
    }

    override fun stopScan() {
        try {
            scanner?.stopScan()
            scanner?.close()
            scanner = null
        } catch (e: Exception) {}
    }

    override fun setScannerLed(enabled: Boolean) {
        try {
            getScanner()?.setLed(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set scanner LED: ${e.message}")
        }
    }
}
