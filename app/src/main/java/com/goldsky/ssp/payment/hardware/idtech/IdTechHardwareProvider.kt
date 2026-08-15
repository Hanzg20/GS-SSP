package com.goldsky.ssp.payment.hardware.idtech

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.goldsky.ssp.payment.hardware.IHardwareProvider
import com.goldsky.ssp.payment.hardware.IScannerProvider
import com.idtechproducts.device.*
import com.idtechproducts.device.ReaderInfo.DEVICE_TYPE

/**
 * ID TECH implementation of IHardwareProvider.
 * Manages NEO2/NEO3 connection via USB or Serial.
 *
 * Owns the single [IdTechPaymentProvider] instance and wires it in as the reader's
 * real [OnReceiverListener] -- previously this class built the reader with a
 * no-op dummy listener, which meant every EMV/MSR/disconnect callback silently
 * went nowhere. [HardwareFactory.getPaymentProvider] pulls the same instance
 * back out via [getPaymentProvider] rather than constructing its own.
 */
class IdTechHardwareProvider : IHardwareProvider, DefaultLifecycleObserver {
    private val TAG = "IdTechHardware"
    private var reader: IDT_NEO2? = null
    private val paymentProvider = IdTechPaymentProvider()
    private var serialProvider: IdTechSerialProvider? = null

    private val dummyPinListener = OnReceiverListenerPIN { _ -> }

    fun getReader(): IDT_NEO2? = reader
    fun getPaymentProvider(): IdTechPaymentProvider = paymentProvider
    override fun getSerialProvider(): IdTechSerialProvider = serialProvider ?: IdTechSerialProvider().also { serialProvider = it }

    override fun getScannerProvider(): IScannerProvider = object : IScannerProvider {
        override fun startScan(callback: IScannerProvider.ScanCallback) {}
        override fun stopScan() {}
        override fun setScannerLed(enabled: Boolean) {}
    }

    override fun init(context: Context) {
        if (reader == null) {
            try {
                // paymentProvider IS the listener -- this is what actually connects
                // the SDK's callbacks to IdTechPaymentProvider's state machine.
                reader = IDT_NEO2(paymentProvider, dummyPinListener, context.applicationContext)
                paymentProvider.attachReader(reader!!)
                reader?.device_setDeviceType(DEVICE_TYPE.DEVICE_NEO2_USB)
                reader?.registerListen()
                Log.i(TAG, "ID TECH NEO2/NEO3 initialized via USB")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate IDT_NEO2: ${e.message}")
            }
        }
    }

    override fun registerLifecycle(context: Context, lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    override fun getSerialNumber(context: Context): String {
        val sb = StringBuilder()
        val ret = reader?.config_getSerialNumber(sb)
        return if (ret == ErrorCode.SUCCESS) sb.toString() else "SN_UNKNOWN"
    }

    override fun getFirmwareVersion(): String {
        val sb = StringBuilder()
        val ret = reader?.device_getFirmwareVersion(sb)
        return if (ret == ErrorCode.SUCCESS) {
            sb.toString()
        } else {
            "FW_UNKNOWN"
        }
    }

    override fun isOperational(): Boolean {
        return try {
            reader != null && reader?.device_isConnected() == true
        } catch (e: Exception) {
            false
        }
    }

    override fun setScreenBrightness(percent: Int) {
        // No-op for ID TECH peripheral
    }

    override fun getScreenBrightness(): Int = 100

    override fun feedWatchdog() {
        // No-op for ID TECH peripheral
    }

    override fun reboot() {
        Log.i(TAG, "MOCK REBOOT: ID TECH peripheral cannot reboot host terminal directly")
    }

    override fun getTamperStatus(): Boolean = false

    override fun release() {
        reader?.unregisterListen()
        reader?.release()
        reader = null
        paymentProvider.detachReader()
        serialProvider?.close()
        serialProvider = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
        super.onDestroy(owner)
    }
}
