package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import com.pax.dal.IScanner
import com.pax.dal.IPicc
import com.pax.dal.entity.EDetectMode
import com.pax.dal.entity.PiccCardInfo
import com.pax.neptunelite.api.NeptuneLiteUser
import kotlinx.coroutines.*
import java.util.Locale

/**
 * Manager for the PAX IM30 hardware peripherals (Scanner and PICC/NFC).
 */
class PaxScannerManager(private val context: Context) {
    companion object {
        private const val TAG = "PaxScannerManager"
        private const val SCAN_TIMEOUT_SEC = 30 
    }

    interface ScanCallback {
        fun onScanSuccess(result: String)
        fun onScanFailure(errorMsg: String)
    }

    interface CardCallback {
        fun onCardDetected(type: String, uid: String)
        fun onDetectionError(error: String)
    }

    private var scanner: IScanner? = null
    private var picc: IPicc? = null
    private var detectionJob: Job? = null
    private val isPaxAvailable: Boolean by lazy { checkPaxAvailability() }

    /**
     * A class being loadable is not proof real PAX hardware is present: the
     * local compile-time stubs under com.pax.** are always on the classpath
     * (they exist so the project builds without the vendor AAR), so
     * Class.forName alone always succeeds -- including on a plain emulator.
     * The real signal is whether getDal() actually returns a working DAL.
     */
    private fun checkPaxAvailability(): Boolean {
        return try {
            Class.forName("com.pax.neptunelite.api.NeptuneLiteUser")
            NeptuneLiteUser.getInstance().getDal(context) != null
        } catch (e: Exception) {
            Log.w(TAG, "NeptuneLite SDK/hardware not available. Running in mock mode: ${e.message}")
            false
        }
    }

    /**
     * Activates the hardware scanner and begins waiting for a barcode/QR scan.
     */
    fun startScan(callback: ScanCallback) {
        if (isPaxAvailable) {
            startHardwareScan(callback)
        } else {
            startMockScan(callback)
        }
    }

    private fun startHardwareScan(callback: ScanCallback) {
        try {
            val dal = NeptuneLiteUser.getInstance().getDal(context)
            scanner = dal.scanner
            scanner?.open()
            scanner?.startScan(SCAN_TIMEOUT_SEC, object : IScanner.ScanListener {
                override fun onSuccess(barcode: String?) {
                    Log.i(TAG, "Hardware scan success: $barcode")
                    stopScan()
                    callback.onScanSuccess(barcode ?: "")
                }

                override fun onFail() {
                    Log.w(TAG, "Hardware scan failed or timed out after ${SCAN_TIMEOUT_SEC}s")
                    callback.onScanFailure("Scanner timeout")
                }
            })
            Log.d(TAG, "Hardware scanner activated, waiting for barcode...")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting hardware scan: ${e.message}")
            callback.onScanFailure("Scanner error: ${e.message}")
        }
    }

    /**
     * Mock scan for development/testing on non-PAX hardware.
     * Simulates a successful scan after 4 seconds.
     */
    private fun startMockScan(callback: ScanCallback) {
        Log.d(TAG, "Mock scanner: will simulate a scan in 4 seconds")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Cycles between a generic payment-code-shaped token and the two
            // seeded values from docs/supabase_full_schema.sql (a member QR
            // code and a coupon code) so both consumers of startScan() --
            // MainActivity's Tech Dashboard raw scanner test and its coupon/
            // member scan entry (initCouponScan(), see
            // docs/coupon_redemption_integration.md) -- are exercisable
            // end-to-end off real hardware.
            val mockResults = listOf(
                "280047291039485${System.currentTimeMillis() % 1000}", // typical Alipay/WeChat-shaped payment token
                "MBRQR6789ABC", // seeded vip_cards.qr_code
                "DEMO-PCT20-COUPON" // seeded coupons.code
            )
            val mockToken = mockResults[((System.currentTimeMillis() / 4000) % mockResults.size).toInt()]
            Log.i(TAG, "Mock scan result: $mockToken")
            callback.onScanSuccess(mockToken)
        }, 4000)
    }

    /**
     * Stops and releases the hardware scanner.
     */
    fun stopScan() {
        if (!isPaxAvailable) return
        try {
            setScannerLed(false)
            scanner?.stopScan()
            scanner?.close()
            scanner = null
            Log.d(TAG, "Hardware scanner stopped and closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scanner: ${e.message}")
        }
    }

    /**
     * Starts background polling for NFC cards (NFC/Mifare).
     */
    fun startCardDetection(callback: CardCallback) {
        if (!isPaxAvailable) {
            startMockCardDetection(callback)
            return
        }

        detectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val dal = NeptuneLiteUser.getInstance().getDal(context)
                picc = dal.picc
                picc?.open()
                Log.d(TAG, "PICC opened, starting detection loop...")

                while (isActive) {
                    val cardInfo = picc?.detect(EDetectMode.ALL)
                    if (cardInfo != null) {
                        val cardType = cardInfo.cardType
                        val uid = bytesToHex(cardInfo.serialInfo ?: byteArrayOf())
                        
                        // Categorize: 0x41/0x42 ('A'/'B') typically have ISO-DEP support if bit 6 is set
                        // For simplicity, if it's Type A/B, we check if it's a payment card.
                        val category = when (cardType.toInt().toChar()) {
                            'M' -> "MIFARE"
                            'A', 'B' -> "ISO_14443"
                            else -> "UNKNOWN"
                        }
                        
                        Log.i(TAG, "Card detected: Type=$category, UID=$uid")
                        withContext(Dispatchers.Main) {
                            callback.onCardDetected(category, uid)
                        }
                        break // Stop after first detection
                    }
                    delay(300) // Poll interval
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection error: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback.onDetectionError(e.message ?: "Unknown PICC error")
                }
            } finally {
                stopCardDetection()
            }
        }
    }

    fun stopCardDetection() {
        detectionJob?.cancel()
        try {
            picc?.close()
        } catch (e: Exception) {}
        picc = null
        Log.d(TAG, "PICC detection stopped")
    }

    private fun startMockCardDetection(callback: CardCallback) {
        Log.d(TAG, "Mock NFC: will simulate card tap in 3 seconds")
        CoroutineScope(Dispatchers.Main).launch {
            delay(3000)
            // Flip a coin to mock VIP or EMV
            if (System.currentTimeMillis() % 2 == 0L) {
                callback.onCardDetected("MIFARE", "VIP_CARD_UID_6789")
            } else {
                callback.onCardDetected("ISO_14443", "EMV_UID_0000")
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }

    /**
     * Controls the physical scan bay illumination.
     */
    fun setScannerLed(enabled: Boolean) {
        if (!isPaxAvailable) return
        try {
            if (scanner == null) {
                val dal = NeptuneLiteUser.getInstance().getDal(context)
                scanner = dal.scanner
            }
            scanner?.setLed(enabled)
            Log.d(TAG, "Scanner LED set to: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set scanner LED: ${e.message}")
        }
    }
}
