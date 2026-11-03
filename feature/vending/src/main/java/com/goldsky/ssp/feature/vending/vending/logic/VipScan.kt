package com.goldsky.ssp.vending.logic

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IScannerProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The VIP member-code scan (the terminal's camera, as Aegis Wash), shared by the MDB sale and
 * the showcase grid. Needs the CAMERA permission and the scan service in the manifest's <queries>.
 */
object VipScan {
    private const val TAG = "VipScan"
    /** How long the code is looked for (Wash's payment-page scan is 30 s too). */
    private const val SCAN_MS = 30_000L

    private fun scanner(context: Context): IScannerProvider? =
        runCatching { HardwareFactory.getScannerProvider(context, "WIZARPOS") }
            .onFailure { Log.e(TAG, "No scanner: ${it.message}") }.getOrNull()

    /** One scan: the code, or null (nothing within [SCAN_MS], no scanner, unreadable). Cancelling closes the camera. */
    suspend fun code(context: Context): String? {
        val sc = scanner(context) ?: return null
        return withTimeoutOrNull(SCAN_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                sc.startScan(object : IScannerProvider.ScanCallback {
                    override fun onScanSuccess(result: String) { if (cont.isActive) cont.resumeWith(Result.success(result)) }
                    override fun onScanFailure(errorMsg: String) {
                        Log.w(TAG, "Scan failed: $errorMsg")
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                })
                // Timed out or cancelled: close the camera (its own "cancelled" callback is ignored above).
                cont.invokeOnCancellation { runCatching { sc.stopScan() } }
            }
        }
    }

    /** Closes the camera if a scan is open. */
    fun stop(context: Context) {
        scanner(context)?.let { runCatching { it.stopScan() } }
    }
}
