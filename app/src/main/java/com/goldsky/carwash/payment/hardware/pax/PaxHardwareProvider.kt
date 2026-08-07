package com.goldsky.carwash.payment.hardware.pax

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.goldsky.carwash.payment.hardware.IHardwareProvider
import com.goldsky.carwash.payment.hardware.IScannerProvider
import com.pax.dal.IDAL
import com.pax.neptunelite.api.NeptuneLiteUser
import com.pax.poslink.POSLinkAndroid

/**
 * PAX implementation of IHardwareProvider.
 * Manages DAL (NeptuneLite) and POSLink initialization.
 *
 * Owns the single [PaxPaymentProvider]/[PaxScannerProvider] instances for
 * this vendor -- mirrors [com.goldsky.carwash.payment.hardware.idtech.IdTechHardwareProvider]
 * owning its [com.goldsky.carwash.payment.hardware.idtech.IdTechPaymentProvider].
 * [HardwareFactory.getPaymentProvider]/[HardwareFactory.getScannerProvider]
 * pull the same instance back out via [getPaymentProvider]/[getScannerProvider]
 * rather than constructing a new one per call, which previously meant
 * [PaxPaymentProvider.updateConfig] state was silently dropped on every call.
 */
class PaxHardwareProvider : IHardwareProvider, DefaultLifecycleObserver {
    private val TAG = "PaxHardware"
    private var dal: IDAL? = null
    private var appContext: Context? = null
    private var paymentProvider: PaxPaymentProvider? = null
    private var scannerProvider: PaxScannerProvider? = null
    private var serialProvider: PaxSerialProvider? = null

    override fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "Initializing PAX Hardware Provider")
        try {
            // 1. Initialize POSLink Android Bridge (Required for BroadPOS AIDL)
            POSLinkAndroid.init(context.applicationContext)

            // 2. Access Device Abstraction Layer (DAL)
            dal = NeptuneLiteUser.getInstance().getDal(context.applicationContext)
            if (dal != null) {
                Log.i(TAG, "PAX DAL initialized successfully")
            } else {
                Log.w(TAG, "PAX DAL not available (Running on non-PAX hardware?) -- dependent providers fall back to mock mode")
            }
        } catch (e: Exception) {
            // Covers the real off-device case: NeptuneLiteUser resolves at
            // compile time against the local stub (see CLAUDE.md "PAX SDK
            // stubs"), so this is where "no real hardware/SDK present"
            // actually surfaces, not a class-loading failure.
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
        // Many older PAX firmware versions don't have a direct GET.
        // Returning a default or tracking last set.
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
        // PAX DAL doesn't usually require explicit release in this version
        dal = null
        paymentProvider = null
        scannerProvider = null
        serialProvider?.close()
        serialProvider = null
    }

    fun getDal(): IDAL? = dal

    /** Lazily creates and reuses a single [PaxPaymentProvider] for this provider's lifetime. */
    fun getPaymentProvider(): PaxPaymentProvider {
        val ctx = requireContext()
        return paymentProvider ?: PaxPaymentProvider(ctx) { dal }.also { paymentProvider = it }
    }

    /** Lazily creates and reuses a single [PaxScannerProvider], sharing this provider's [dal]. */
    override fun getScannerProvider(): PaxScannerProvider {
        val ctx = requireContext()
        return scannerProvider ?: PaxScannerProvider(ctx) { dal }.also { scannerProvider = it }
    }

    override fun getSerialProvider(): PaxSerialProvider {
        return serialProvider ?: PaxSerialProvider { dal }.also { serialProvider = it }
    }

    private fun requireContext(): Context =
        appContext ?: error("PaxHardwareProvider.init() must be called before requesting its payment/scanner provider")

    override fun onDestroy(owner: LifecycleOwner) {
        release()
        super.onDestroy(owner)
    }
}
