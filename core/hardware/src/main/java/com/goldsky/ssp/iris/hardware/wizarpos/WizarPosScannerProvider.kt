package com.goldsky.ssp.iris.hardware.wizarpos

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cloudpos.advance.ext.POSTerminalAdvance
import com.cloudpos.advance.ext.scanner.IScanCallBack
import com.cloudpos.advance.ext.scanner.IScannerDevice
import com.cloudpos.advance.ext.scanner.ScanParameter
import com.cloudpos.advance.ext.scanner.ScanResult
import com.goldsky.ssp.payment.hardware.IScannerProvider
import java.util.concurrent.Executors

/**
 * WizarPOS Scanner implementation using CloudPOS Advance SDK.
 */
class WizarPosScannerProvider(private val context: Context) : IScannerProvider {

    companion object {
        private const val TAG = "WizarPosScanner"
    }

    private var scannerDevice: IScannerDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ScannerAdvanceDeviceImpl.open() is a blocking device-I/O call. Callers
    // (MainActivity.initCouponScan()) invoke startScan() directly from an
    // OnClickListener with no background dispatch of their own, so running
    // open()/startScan() on the calling thread throws a "Time-consuming
    // operation on main thread" exception and fails every single attempt --
    // confirmed on real WizarPOS Q3mini hardware 2026-09-18, not a
    // theoretical concern. MainActivity's onScanSuccess already wraps its
    // own body in runOnUiThread, i.e. it already expects this callback may
    // arrive off the main thread -- this class just wasn't backgrounding the
    // blocking part in the first place.
    private val ioExecutor = Executors.newSingleThreadExecutor()

    /** Why the last open failed, for the failure message (production units have no adb). */
    private var openError: String? = null

    @Volatile private var previewWindow: android.graphics.Rect? = null

    // Bumped by stopScan(). A startScan() queued on ioExecutor before a stop
    // must not run after it: on the Q2 a stop pressed right after a scan
    // landed between the next run's startScan() call and its execution, so
    // the camera came straight back and the user couldn't get out (2026-10-10).
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    override fun setPreviewWindow(window: android.graphics.Rect?) {
        previewWindow = window
    }

    private fun ensureOpened(): Boolean {
        if (scannerDevice == null) {
            try {
                scannerDevice = POSTerminalAdvance.getInstance().getScannerDevice()
                if (scannerDevice == null) openError = "getScannerDevice() returned null"
                scannerDevice?.open(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open WizarPOS scanner: ${e.message}")
                openError = "${e.javaClass.simpleName}: ${e.message}"
                scannerDevice = null
                return false
            }
        }
        val opened = scannerDevice?.isOpened ?: false
        if (!opened && openError == null) openError = "open() returned but isOpened=false"
        return opened
    }

    override fun startScan(callback: IScannerProvider.ScanCallback) {
        val gen = generation.get()
        ioExecutor.execute {
            if (gen != generation.get()) {
                Log.d(TAG, "Dropping a start queued before stopScan()")
                mainHandler.post { callback.onScanFailure("Stopped") }
                return@execute
            }
            if (!ensureOpened()) {
                val why = openError ?: "unknown"
                mainHandler.post { callback.onScanFailure("Hardware initialization failed: $why") }
                return@execute
            }

            try {
                // camera_index 0 is the main scan head (the bottom window),
                // not the "front" camera -- an earlier log line calling it
                // "front" was just AbstractCameraMgr's generic debug label,
                // not an accurate physical description; confirmed by trying
                // index 1 ("secondary camera" per vendor guidance), which
                // throws "Unknown camera ID" on this unit (no second camera
                // module present). Do NOT set KEY_DECODEFORMAT -- confirmed
                // on real hardware 2026-09-18 that passing it (even with a
                // format the SDK should support, "QR, Code128") makes
                // AbstractScannerMgr self-abort within ~7ms of startScan
                // (CameraDecodeHelper.stopCameraAndDecode / OverlayUiMgr.stopUi
                // fire almost immediately, callback reports "Scan error: -1"
                // before the camera preview even shows) -- vendor's own
                // reference implementation for this device only sets
                // KEY_CAMERA_INDEX, nothing else.
                val param = ScanParameter()
                param.set(ScanParameter.KEY_CAMERA_INDEX, 0)
                // A preview window means the caller wants its own UI kept visible:
                // "overlay" draws the preview over the app (the app stays resumed),
                // where the default on the Q2 is a full-screen ScannerActivity that
                // ignores the window keys. The window keys are in dp -- 537 "px"
                // drew 1074 px tall at density 2.0 (real Q2, 2026-10-10). The close
                // button is how the user ends a run of scans.
                previewWindow?.let { w ->
                    val density = context.resources.displayMetrics.density
                    fun dp(px: Int) = (px / density).toInt()
                    param.set(ScanParameter.KEY_SCAN_MODE, "overlay")
                    param.set(ScanParameter.KEY_UI_WINDOW_LEFT, dp(w.left))
                    param.set(ScanParameter.KEY_UI_WINDOW_TOP, dp(w.top))
                    param.set(ScanParameter.KEY_UI_WINDOW_WIDTH, dp(w.width()))
                    param.set(ScanParameter.KEY_UI_WINDOW_HEIGHT, dp(w.height()))
                    param.set(ScanParameter.KEY_REQUIRE_CLOSE_BUTTON, true)
                }
                // foundBarcode() is meant to be one-shot per startScan(), but the
                // stopScan() call below -- needed to release the device between
                // scans -- makes the vendor SDK invoke this same callback again a
                // moment later with a non-success result code. That's the exact
                // same self-inflicted-callback-after-stop() quirk MainActivity
                // already guards against for its own explicit stopScan() call
                // (see initCouponScan()'s isScanTimingOut), just triggered here by
                // our internal stop instead. Left unguarded, that stray second
                // call reached MainActivity.onScanFailure() AFTER a real
                // onScanSuccess() and overwrote the success banner with "couldn't
                // read, try again" -- confirmed on real hardware 2026-09-19 via
                // logcat (onScanSuccess for a coupon, then "Scan error: 0" ~660ms
                // later) cross-referenced against the coupons table, which showed
                // the redemption had genuinely gone through (uses_count=1) despite
                // the terminal ending on a failure message.
                var delivered = false
                scannerDevice?.startScan(param, object : IScanCallBack {
                    override fun foundBarcode(result: ScanResult) {
                        if (delivered) {
                            Log.d(TAG, "Ignoring stray foundBarcode after result already delivered: code=${result.resultCode}")
                            return
                        }
                        delivered = true
                        if (result.resultCode == ScanResult.SCAN_SUCCESS) {
                            mainHandler.post { callback.onScanSuccess(result.text ?: "") }
                        } else {
                            mainHandler.post { callback.onScanFailure("Scan error: ${result.resultCode}") }
                        }
                        try {
                            scannerDevice?.stopScan()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to stop scan after result: ${e.message}")
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Start scan error: ${e.message}")
                mainHandler.post { callback.onScanFailure("Internal error: ${e.message}") }
            }
        }
    }

    override fun stopScan() {
        generation.incrementAndGet()
        ioExecutor.execute {
            try {
                scannerDevice?.stopScan()
                scannerDevice?.close()
                scannerDevice = null
            } catch (e: Exception) {
                Log.e(TAG, "Stop scan error: ${e.message}")
            }
        }
    }

    override fun setScannerLed(enabled: Boolean) {
    }
}
