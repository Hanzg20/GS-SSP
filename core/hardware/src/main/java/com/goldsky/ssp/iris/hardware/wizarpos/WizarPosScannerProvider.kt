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
        ioExecutor.execute {
            if (!ensureOpened()) {
                mainHandler.post { callback.onScanFailure("Hardware initialization failed") }
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
                scannerDevice?.startScan(param, object : IScanCallBack {
                    override fun foundBarcode(result: ScanResult) {
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
