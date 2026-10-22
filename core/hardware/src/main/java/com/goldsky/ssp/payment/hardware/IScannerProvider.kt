package com.goldsky.ssp.payment.hardware

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

    /**
     * Confine a camera scanner's preview to this screen rectangle (pixels)
     * instead of full screen, so the app's UI stays visible while scanning;
     * null = full screen. No-op for scanners without an on-screen preview.
     */
    fun setPreviewWindow(window: android.graphics.Rect?) {}

    /** What decides whether the scanner can open (service, permission, camera, last good scan); "" if nothing to say. */
    fun diagnostics(): String = ""
}
