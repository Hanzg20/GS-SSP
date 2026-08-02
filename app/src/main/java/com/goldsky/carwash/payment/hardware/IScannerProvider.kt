package com.goldsky.carwash.payment.hardware

/**
 * Common interface for barcode/QR scanning across different vendors.
 */
interface IScannerProvider {

    interface ScanCallback {
        fun onScanSuccess(result: String)
        fun onScanFailure(errorMsg: String)
    }

    /**
     * Starts the scanning process.
     */
    fun startScan(callback: ScanCallback)

    /**
     * Stops the scanning process.
     */
    fun stopScan()

    /**
     * Controls the scanner's illumination LED.
     */
    fun setScannerLed(enabled: Boolean)
}
