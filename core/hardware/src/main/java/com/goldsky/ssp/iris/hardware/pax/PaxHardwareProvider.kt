package com.goldsky.ssp.iris.hardware.pax

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.goldsky.ssp.payment.hardware.*
import com.pax.dal.IDAL
import com.pax.neptunelite.api.NeptuneLiteUser
import com.pax.poslink.POSLinkAndroid

/**
 * PAX implementation of IHardwareProvider.
 * Manages DAL (NeptuneLite) and POSLink initialization.
 */
class PaxHardwareProvider : IHardwareProvider, DefaultLifecycleObserver {
    private val TAG = "PaxHardware"
    private var dal: IDAL? = null
    private var appContext: Context? = null
    private var paymentProvider: PaxPaymentProvider? = null
    private var scannerProvider: PaxScannerProvider? = null
    private var serialProvider: PaxSerialProvider? = null
    private var gpioProvider: PaxGpioProvider? = null
    private var mdbProvider: PaxMdbProvider? = null
    private var printerProvider: PaxPrinterProvider? = null

    override fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "Initializing PAX Hardware Provider (UPTAPI)")
        
        if (com.goldsky.ssp.core.hardware.BuildConfig.IS_MOCK) {
            Log.w(TAG, "MOCK MODE ENABLED: Skipping real PAX SDK initialization")
            return
        }

        try {
            // 1. Initialize POSLink Android Bridge (Required for BroadPOS AIDL)
            POSLinkAndroid.init(context.applicationContext)

            // 2. Initialize UPTAPI Managers (Implicitly handles binding)
            pax.util.MiscManager.getInstance()
            pax.util.DigitalIOManager.getInstance()
            
            // 3. Fallback: Access DAL for legacy support (SN/Firmware)
            dal = NeptuneLiteUser.getInstance().getDal(context.applicationContext)
            
            Log.i(TAG, "PAX UPTAPI and DAL ready")
        } catch (e: Throwable) {
            // Throwable, not Exception: on non-PAX hardware (e.g. a WizarPOS
            // unit, since every app shell registers all 3 vendor providers
            // unconditionally at startup -- see AegisWashApplication) PAX's
            // native libs (libpaxmiscmanager.so etc.) simply aren't present,
            // and that surfaces as UnsatisfiedLinkError/NoClassDefFoundError
            // (an Error, not an Exception) from pax.util.MiscManager's static
            // initializer -- confirmed crashing Application.onCreate() on a
            // real WizarPOS Q3mini, 2026-09-18, once IS_MOCK stopped gating
            // this unconditionally. One vendor's missing hardware must never
            // take down the whole app.
            Log.e(TAG, "Failed to initialize PAX Hardware: ${e.message}")
        }
    }

    override fun registerLifecycle(context: Context, lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    override fun getSerialNumber(context: Context): String {
        return try {
            dal?.sys?.termSerial ?: "PAX_UNKNOWN_SN"
        } catch (e: Exception) {
            "PAX_ERROR_SN"
        }
    }

    override fun getFirmwareVersion(): String {
        return try {
            dal?.sys?.firmwareVersion ?: "FW_UNKNOWN"
        } catch (e: Exception) {
            "FW_ERROR"
        }
    }

    override fun isOperational(): Boolean {
        return dal != null
    }

    override fun setScreenBrightness(percent: Int) {
        try {
            dal?.sys?.setScreenBrightness(percent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness: ${e.message}")
        }
    }

    override fun getScreenBrightness(): Int {
        return 100 
    }

    override fun feedWatchdog() {
        try {
            dal?.deviceControl?.watchdogFeed()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to feed watchdog: ${e.message}")
        }
    }

    override fun release() {
        dal = null
        paymentProvider = null
        scannerProvider = null
        serialProvider?.close()
        serialProvider = null
    }

    fun getDal(): IDAL? = dal

    /**
     * Lazily creates and reuses a single [PaxPaymentProvider] for this provider's lifetime.
     * No longer passes [dal] through -- PaxPaymentProvider's VIP-card detection uses POSLink's
     * own PiccManager now, not the NeptuneLite DAL (see that class's startCardDetection doc).
     */
    fun getPaymentProvider(): PaxPaymentProvider {
        val ctx = requireContext()
        return paymentProvider ?: PaxPaymentProvider(ctx).also { paymentProvider = it }
    }

    /** Lazily creates and reuses a single [PaxScannerProvider], sharing this provider's [dal]. */
    override fun getScannerProvider(): IScannerProvider {
        val ctx = requireContext()
        return scannerProvider ?: PaxScannerProvider(ctx) { dal }.also { scannerProvider = it }
    }

    override fun getPrinterProvider(): IPrinterProvider {
        return printerProvider ?: PaxPrinterProvider { dal }.also { printerProvider = it }
    }

    override fun getSerialProvider(): ISerialProvider {
        return serialProvider ?: PaxSerialProvider { dal }.also { serialProvider = it }
    }

    override fun getGpioProvider(): IGpioProvider {
        return gpioProvider ?: PaxGpioProvider().also { gpioProvider = it }
    }

    override fun getMdbProvider(): IMdbProvider {
        val ctx = requireContext()
        return mdbProvider ?: PaxMdbProvider(ctx).also { mdbProvider = it }
    }

    override fun reboot() {
        try {
            Log.w(TAG, "Hardware REBOOT triggered via DAL")
            dal?.sys?.reboot()
        } catch (e: Exception) {
            Log.e(TAG, "Reboot failed: ${e.message}")
        }
    }

    override fun getTamperStatus(): Boolean {
        return try {
            dal?.ped?.getTamperStatus() ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun requireContext(): Context =
        appContext ?: error("PaxHardwareProvider.init() must be called before requesting its payment/scanner provider")

    override fun onDestroy(owner: LifecycleOwner) {
        release()
        super.onDestroy(owner)
    }
}
