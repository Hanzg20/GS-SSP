package com.goldsky.ssp.iris.hardware.wizarpos

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.cloudpos.POSTerminal
import com.cloudpos.advance.ext.POSTerminalAdvance
import com.goldsky.ssp.core.hardware.BuildConfig
import com.goldsky.ssp.payment.hardware.*
import com.goldsky.ssp.payment.wizarpos.WizarPosPaymentProvider

/**
 * WizarPOS Hardware Provider implementation using CloudPOS SDK.
 *
 * Model-agnostic across WizarPOS's Android lineup (Q3mini, Q3PRO, D3 "Smart
 * ECR", etc.) -- `POSTerminal.getInstance(context)` and the P3 local-socket
 * payment integration (WizarPosPaymentProvider/WizarPosSocketClient, talking
 * to the co-located PAYWizard app on 127.0.0.1:6666) are both CloudPOS SDK
 * abstractions that don't hardcode a specific device model. Confirmed
 * 2026-08-27 against WizarPOS's own SDK docs (smartpossdk.gitbook.io) while
 * scoping D3 "Smart ECR" integration: no code changes were needed to target
 * D3, only real-hardware testing (not done -- no D3 unit available). The
 * GPIO/MDB providers below (WizarPosGpioProvider/WizarPosMdbProvider) ARE
 * Q3mini-UPT-specific in practice (vending-machine relay/MDB control) -- a
 * "Smart ECR" companion terminal like D3 wouldn't be expected to use those,
 * but nothing prevents calling them if a given unit happens to expose that
 * hardware.
 */
class WizarPosHardwareProvider : IHardwareProvider {
    
    companion object {
        private const val TAG = "WizarPosHardware"
    }
    
    private var terminal: POSTerminal? = null
    private var scannerProvider: IScannerProvider? = null
    private var printerProvider: WizarPosPrinterProvider? = null
    private var serialProvider: WizarPosSerialProvider? = null
    private var paymentProvider: WizarPosPaymentProvider? = null
    private var gpioProvider: WizarPosGpioProvider? = null
    private var mdbProvider: WizarPosMdbProvider? = null
    private var context: Context? = null

    override fun init(context: Context) {
        this.context = context
        Log.i(TAG, "Initializing WizarPOS Hardware Provider")

        if (BuildConfig.IS_MOCK) {
            Log.w(TAG, "MOCK MODE ENABLED: Skipping real WizarPOS SDK initialization")
            return
        }

        try {
            // POSTerminal.getInstance(context) handles the binding to the background service.
            // There is no explicit .open() method on the POSTerminal class itself.
            terminal = POSTerminal.getInstance(context)
            Log.i(TAG, "WizarPOS CloudPOS SDK instance retrieved")
        } catch (e: Throwable) {
            // Throwable, not Exception: see PaxHardwareProvider's init() for
            // why -- every app shell registers all 3 vendor providers
            // unconditionally, so this must degrade gracefully on non-WizarPOS
            // hardware too instead of taking down the whole app.
            Log.e(TAG, "Failed to get WizarPOS terminal instance: ${e.message}")
        }
    }

    override fun registerLifecycle(context: Context, lifecycleOwner: LifecycleOwner) {
    }

    override fun getSerialNumber(context: Context): String {
        return try {
            terminal?.terminalSpec?.serialNumber ?: "WIZAR_SN_UNKNOWN"
        } catch (e: Exception) {
            "WIZAR_SN_ERROR"
        }
    }

    override fun getFirmwareVersion(): String {
        return try {
            // Returns the OS build number/display name which is standard for WizarPOS firmware tracking
            android.os.Build.DISPLAY
        } catch (e: Exception) {
            "FW_UNKNOWN"
        }
    }

    override fun isOperational(): Boolean {
        return terminal != null
    }

    override fun setScreenBrightness(percent: Int) {
    }

    override fun getScreenBrightness(): Int = 80

    override fun getScannerProvider(): IScannerProvider {
        if (scannerProvider == null && context != null) {
            // Confirmed on real WizarPOS Q3mini hardware 2026-09-18: this
            // unit has no bottom hardware scan engine license installed
            // (WizarPosHoneywellScannerProvider's AIDL plumbing to
            // com.wizarpos.honeywell.scanner works correctly -- open/start
            // both return true -- but its own HoneywellDecoderImpl refuses
            // to activate: "activate license failed, license is null").
            // That path is kept in the codebase (see its own doc comment)
            // in case a license ever gets provisioned, but is NOT wired in
            // here -- the camera-based path is this device's only working
            // scanner today.
            scannerProvider = WizarPosScannerProvider(context!!)
        }
        return scannerProvider!!
    }

    override fun getPrinterProvider(): IPrinterProvider {
        if (printerProvider == null) {
            printerProvider = WizarPosPrinterProvider(terminal)
        }
        return printerProvider!!
    }

    fun getPaymentProvider(): IPaymentProvider {
        if (paymentProvider == null) {
            paymentProvider = WizarPosPaymentProvider(terminal)
        }
        return paymentProvider!!
    }

    override fun getGpioProvider(): IGpioProvider {
        if (gpioProvider == null) {
            gpioProvider = WizarPosGpioProvider(terminal)
        }
        return gpioProvider!!
    }

    override fun getMdbProvider(): IMdbProvider {
        if (mdbProvider == null) {
            mdbProvider = WizarPosMdbProvider(terminal)
        }
        return mdbProvider!!
    }

    override fun feedWatchdog() {
    }

    override fun getSerialProvider(): ISerialProvider {
        if (serialProvider == null) {
            serialProvider = WizarPosSerialProvider(terminal)
        }
        return serialProvider!!
    }

    /**
     * Through WizarPOS's system extension (ISystemDevice, served by the
     * preinstalled com.wizarpos.wizarviewagentassistant). This was an empty
     * body, so every remote REBOOT reported SUCCESS and nothing happened.
     */
    override fun reboot(): Boolean {
        val ctx = context ?: return false.also { Log.e(TAG, "Reboot failed: provider not initialised") }
        return try {
            val system = POSTerminalAdvance.getInstance().systemDevice
            if (!system.isOpened && !system.open(ctx)) {
                Log.e(TAG, "Reboot failed: could not open WizarPOS system device")
                return false
            }
            Log.w(TAG, "Hardware REBOOT via WizarPOS ISystemDevice")
            system.reboot()
        } catch (e: Exception) {
            Log.e(TAG, "Reboot failed: ${e.message}")
            false
        }
    }

    override fun getTamperStatus(): Boolean = false

    override fun release() {
        try {
            scannerProvider?.stopScan()
            serialProvider?.close()
            mdbProvider?.stopPolling()
            gpioProvider?.release()
            terminal = null
            Log.i(TAG, "WizarPOS SDK released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WizarPOS SDK: ${e.message}")
        }
    }
}
