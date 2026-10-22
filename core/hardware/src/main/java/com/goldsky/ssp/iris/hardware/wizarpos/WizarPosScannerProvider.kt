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
class WizarPosScannerProvider(context: Context) : IScannerProvider {
    // The application, never the Activity this was created from: the SDK binds the scan
    // service with this context on every open(), and a binding from an Activity that has
    // since been destroyed is refused ("Binding with unknown activity") -- open() then
    // returns false within milliseconds. That was the "scanner won't open" on 253 and 255
    // (2026-10-21 / 22): every time the kiosk Activity was recreated (OPC covering it, a
    // HOME relaunch), scans failed until the app restarted.
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "WizarPosScanner"
        private const val MIN_RUN_MS = 1000L
        // A failed open is retried once after this. (253's 1-37 ms refusals on 2026-10-21
        // were the destroyed-Activity binding, see [context], not the scan service.)
        private const val RETRY_MS = 1000L
        private const val SCAN_SERVICE = "com.cloudpos.scanserver"
    }

    /** When a scan last read a code (0 = not in this process). */
    @Volatile private var lastScanOkAt = 0L

    // Camera availability as the system reports it: false while another app holds the camera.
    private val cameraFree = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    init {
        runCatching {
            val cm = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            cm?.registerAvailabilityCallback(object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) { cameraFree[cameraId] = true }
                override fun onCameraUnavailable(cameraId: String) { cameraFree[cameraId] = false }
            }, mainHandler)
        }.onFailure { Log.w(TAG, "No camera availability callback: ${it.message}") }
    }

    override fun diagnostics(): String {
        val pm = context.packageManager
        val service = runCatching {
            val info = pm.getPackageInfo(SCAN_SERVICE, 0)
            "scan service ${info.versionName}" + if (info.applicationInfo?.enabled == false) " DISABLED" else ""
        }.getOrDefault("scan service NOT INSTALLED")
        val camPerm = if (context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) "camera permission ok" else "camera permission DENIED"
        val cams = if (cameraFree.isEmpty()) "camera state unknown" else cameraFree.entries.sortedBy { it.key }.joinToString(" ") { "cam${it.key}=" + if (it.value) "free" else "IN USE" }
        val last = if (lastScanOkAt == 0L) "no scan read yet" else "last read ${(System.currentTimeMillis() - lastScanOkAt) / 60_000} min ago"
        return "$service; $camPerm; $cams; $last"
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

    // Bumped by every startScan() and stopScan() call; a queued task only acts
    // if no later call superseded it. Two Q2 races (2026-10-10):
    // - a start queued just before a stop ran after it and reopened the camera;
    // - the scan service handles a start asynchronously (its camera window
    //   shows ~0.3-0.65 s after the request). A stop arriving before that is
    //   processed first; the start then shows a window the service no longer
    //   tracks, so no later stop closes it -- it stays until the service
    //   crashes ~30 s later (ForegroundServiceDidNotStartInTimeException).
    //   Hence a stop is held until MIN_RUN_MS after the last start.
    private val requestSeq = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastStartAt = 0L

    override fun setPreviewWindow(window: android.graphics.Rect?) {
        previewWindow = window
    }

    /** [openOnce], and once more after [RETRY_MS] with a fresh device handle; diagnostics added on failure. */
    private fun ensureOpened(): Boolean {
        openError = null
        if (openOnce()) return true
        Log.w(TAG, "Scanner open failed (${openError}); retrying in ${RETRY_MS} ms")
        runCatching { scannerDevice?.close() }
        scannerDevice = null
        openError = null
        Thread.sleep(RETRY_MS)
        if (openOnce()) { Log.i(TAG, "Scanner opened on the retry"); return true }
        openError = "${openError ?: "unknown"} (retried once) -- ${diagnostics()}"
        return false
    }

    private fun openOnce(): Boolean {
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
        val seq = requestSeq.incrementAndGet()
        lastStartAt = android.os.SystemClock.elapsedRealtime()
        ioExecutor.execute {
            if (seq != requestSeq.get()) {
                Log.d(TAG, "Dropping start #$seq, superseded by #${requestSeq.get()}")
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
                            lastScanOkAt = System.currentTimeMillis()
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
        val seq = requestSeq.incrementAndGet()
        val wait = (lastStartAt + MIN_RUN_MS - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
        // Only overlay mode (a preview window was set -- Iris) showed the
        // orphaned-window race; full-screen callers (Wash, Timer) keep the
        // immediate, in-order stop their stop-then-start sequences rely on.
        if (previewWindow == null || wait == 0L) {
            ioExecutor.execute { stopAndClose() }
            return
        }
        Log.d(TAG, "stopScan #$seq held ${wait} ms until the last start has landed")
        mainHandler.postDelayed({
            // A start requested meanwhile supersedes this stop.
            if (seq == requestSeq.get()) ioExecutor.execute { stopAndClose() }
        }, wait)
    }

    private fun stopAndClose() {
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
