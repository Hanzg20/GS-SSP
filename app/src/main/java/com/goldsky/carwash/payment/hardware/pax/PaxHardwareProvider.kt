package com.goldsky.carwash.payment.hardware.pax

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.goldsky.carwash.payment.hardware.IHardwareProvider
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

    override fun init(context: Context) {
        try {
            // 1. Initialize POSLink Android Bridge (Required for BroadPOS AIDL)
            POSLinkAndroid.init(context.applicationContext)
            
            // 2. Access Device Abstraction Layer (DAL)
            dal = NeptuneLiteUser.getInstance().getDal(context.applicationContext)
            if (dal != null) {
                Log.i(TAG, "PAX DAL initialized successfully")
            } else {
                Log.w(TAG, "PAX DAL not available (Running on non-PAX hardware?)")
            }
        } catch (e: Exception) {
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

    override fun release() {
        // PAX DAL doesn't usually require explicit release in this version
        dal = null
    }

    fun getDal(): IDAL? = dal

    override fun onDestroy(owner: LifecycleOwner) {
        release()
        super.onDestroy(owner)
    }
}
