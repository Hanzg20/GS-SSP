package com.goldsky.carwash.payment.hardware

import android.content.Context
import androidx.lifecycle.LifecycleOwner

/**
 * Common interface for hardware-specific system operations and lifecycle management.
 */
interface IHardwareProvider {
    /**
     * Initializes the hardware SDK.
     */
    fun init(context: Context)

    /**
     * Binds the hardware to the activity lifecycle.
     */
    fun registerLifecycle(context: Context, lifecycleOwner: LifecycleOwner)

    /**
     * Retrieves the physical serial number of the terminal.
     */
    fun getSerialNumber(context: Context): String

    /**
     * Retrieves the current firmware version of the device.
     */
    fun getFirmwareVersion(): String

    /**
     * Checks if the hardware is properly connected and functioning.
     */
    fun isOperational(): Boolean

    /**
     * Releases hardware resources.
     */
    fun release()
}
