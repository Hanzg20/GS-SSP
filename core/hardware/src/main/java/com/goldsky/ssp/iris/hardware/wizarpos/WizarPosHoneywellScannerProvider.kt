package com.goldsky.ssp.iris.hardware.wizarpos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.cloudpos.scanserver.aidl.IScanCallBack
import com.cloudpos.scanserver.aidl.ScanParameter
import com.cloudpos.scanserver.aidl.ScanResult
import com.goldsky.ssp.payment.hardware.IScannerProvider
import com.wizarpos.honeywell.scanner.aidl.IHoneywellScanner
import java.util.concurrent.Executors

/**
 * Drives the Q3mini UPT's bottom-mounted hardware scan engine directly,
 * bypassing com.cloudpos.advance.ext.scanner entirely.
 *
 * That "advance ext" API (see [WizarPosScannerProvider]) only ever opens the
 * unit's single registered camera (system property `wp.camerasensor.front` /
 * `persist.wp.camera.face=front` -- confirmed via `adb shell getprop` there
 * is no `wp.camerasensor.rear.*` entry at all) -- confirmed on real hardware
 * 2026-09-18 that neither camera_index=0 nor 1 reaches the bottom window
 * (0 opens the front camera preview, 1 throws "Unknown camera ID"), and
 * `com.cloudpos.scanserver`'s own internal `existQ3pdaScanner()` check
 * returns false, so it silently falls back to camera-based "dialog" mode
 * regardless of scan_mode.
 *
 * The real bottom hardware engine is driven by a separate installed app,
 * `com.wizarpos.honeywell.scanner` (there's also a `com.wizarpos.zebra.scanner`
 * package present -- not tried, Honeywell was tried first since its
 * KEY_DISABLE_HONEYWELL_UI constant is the one that showed up in
 * CameraWindow's own UiParameter logging). It exposes an exported service,
 * `.service.MainService`, bound to an AIDL interface (`IHoneywellScanner`)
 * that is NOT part of the CloudPOS SDK we have -- reconstructed via dexdump
 * of the installed APK (version 1.3.26). See IHoneywellScanner.aidl's own
 * comment for the transaction-code/method-order details that matter for
 * correctness.
 *
 * Status as of 2026-09-18, confirmed on real hardware: this class's AIDL
 * plumbing is CORRECT -- bind, openBySecurity(), and startScanByParameter()
 * all succeed (openBySecurity()'s token argument, a fresh Binder(), was a
 * guess and turned out to be right -- it returns true). The blocker is
 * entirely on the vendor side: com.wizarpos.honeywell.scanner's own
 * HoneywellDecoderImpl refuses to activate --
 * "activate license failed, license is null" -- meaning this specific unit
 * has no Honeywell engine license provisioned. WizarPosHardwareProvider does
 * NOT wire this class in (uses WizarPosScannerProvider, the camera-based
 * path, instead) until a license is confirmed installed on a real unit.
 * Re-verify this class still works once that happens -- it was never
 * exercised past openBySecurity()/startScanByParameter() returning true,
 * i.e. a real foundBarcode() callback firing is still unconfirmed.
 */
class WizarPosHoneywellScannerProvider(private val context: Context) : IScannerProvider {

    companion object {
        private const val TAG = "HoneywellScanner"
        private const val SERVICE_PACKAGE = "com.wizarpos.honeywell.scanner"
        private const val SERVICE_CLASS = "com.wizarpos.honeywell.scanner.service.MainService"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var honeywellService: IHoneywellScanner? = null
    private var opened = false
    private var pendingCallback: IScannerProvider.ScanCallback? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "onServiceConnected")
            honeywellService = IHoneywellScanner.Stub.asInterface(binder)
            val cb = pendingCallback
            pendingCallback = null
            ioExecutor.execute {
                openAndMaybeScan(cb)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected")
            honeywellService = null
            opened = false
        }
    }

    // openBySecurity()/startScanByParameter() are synchronous Binder IPC
    // calls into another app's process -- same "blocking call on the calling
    // thread" hazard confirmed against com.cloudpos.scanserver earlier this
    // session (WizarPosScannerProvider), so this stays off the caller's
    // thread (onServiceConnected already arrives on the main thread; do not
    // call the service directly from there).
    private fun openAndMaybeScan(callback: IScannerProvider.ScanCallback?) {
        val service = honeywellService
        if (service == null) {
            mainHandler.post { callback?.onScanFailure("Honeywell service not connected") }
            return
        }
        try {
            opened = service.openBySecurity(Binder())
        } catch (e: Exception) {
            Log.e(TAG, "openBySecurity threw: ${e.message}")
            opened = false
        }
        Log.i(TAG, "openBySecurity result=$opened")
        if (!opened) {
            mainHandler.post { callback?.onScanFailure("openBySecurity failed") }
            return
        }
        if (callback != null) {
            startScanInternal(service, callback)
        }
    }

    private fun ensureBound(callback: IScannerProvider.ScanCallback): Boolean {
        if (honeywellService != null && opened) return true
        pendingCallback = callback
        val intent = Intent().apply {
            component = ComponentName(SERVICE_PACKAGE, SERVICE_CLASS)
        }
        return try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "bindService failed: ${e.message}")
            false
        }
    }

    override fun startScan(callback: IScannerProvider.ScanCallback) {
        val service = honeywellService
        if (service != null && opened) {
            ioExecutor.execute { startScanInternal(service, callback) }
            return
        }
        if (!ensureBound(callback)) {
            callback.onScanFailure("Failed to bind Honeywell scanner service")
        }
        // else: connection.onServiceConnected() picks up pendingCallback and continues.
    }

    private fun startScanInternal(service: IHoneywellScanner, callback: IScannerProvider.ScanCallback) {
        val callbackStub = object : IScanCallBack.Stub() {
            override fun foundBarcode(result: ScanResult?) {
                if (result?.resultCode == ScanResult.SCAN_SUCCESS) {
                    mainHandler.post { callback.onScanSuccess(result.text ?: "") }
                } else {
                    mainHandler.post { callback.onScanFailure("Scan error: ${result?.resultCode}") }
                }
                try {
                    service.stopScan()
                } catch (e: Exception) {
                    Log.e(TAG, "stopScan after result failed: ${e.message}")
                }
            }
        }
        try {
            val param = ScanParameter()
            val started = service.startScanByParameter(param, callbackStub.asBinder())
            Log.i(TAG, "startScanByParameter result=$started")
            if (!started) {
                mainHandler.post { callback.onScanFailure("startScanByParameter returned false") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startScanByParameter threw: ${e.message}")
            mainHandler.post { callback.onScanFailure("Internal error: ${e.message}") }
        }
    }

    override fun stopScan() {
        ioExecutor.execute {
            try {
                honeywellService?.stopScan()
            } catch (e: Exception) {
                Log.e(TAG, "stopScan failed: ${e.message}")
            }
        }
    }

    override fun setScannerLed(enabled: Boolean) {
    }
}
