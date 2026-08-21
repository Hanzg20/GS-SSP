package com.goldsky.ssp.payment.hardware.wizarpos

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.cloudpos.POSTerminal
import com.goldsky.ssp.payment.hardware.*

/**
 * WizarPOS Hardware Provider implementation using CloudPOS SDK.
 */
class WizarPosHardwareProvider : IHardwareProvider {
    
    companion object {
        private const val TAG = "WizarPosHardware"
    }
    
    private var terminal: POSTerminal? = null
    private var scannerProvider: WizarPosScannerProvider? = null
    private var serialProvider: WizarPosSerialProvider? = null
    private var paymentProvider: WizarPosPaymentProvider? = null
    private var gpioProvider: WizarPosGpioProvider? = null
    private var mdbProvider: WizarPosMdbProvider? = null
    private var context: Context? = null

    override fun init(context: Context) {
        this.context = context
        try {
            // POSTerminal.getInstance(context) handles the binding to the background service.
            // There is no explicit .open() method on the POSTerminal class itself.
            terminal = POSTerminal.getInstance(context)
            Log.i(TAG, "WizarPOS CloudPOS SDK instance retrieved")
        } catch (e: Exception) {
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
            scannerProvider = WizarPosScannerProvider(context!!)
        }
        return scannerProvider!!
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

    override fun reboot() {
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
