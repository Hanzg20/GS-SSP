package com.goldsky.ssp.payment.hardware

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
     * Sets the screen brightness.
     * @param percent 0-100
     */
    fun setScreenBrightness(percent: Int)

    /**
     * Gets the current screen brightness.
     */
    fun getScreenBrightness(): Int

    /**
     * Gets the scanner provider for the current hardware.
     */
    fun getScannerProvider(): IScannerProvider

    /**
     * Feeds the hardware watchdog to prevent system reboot.
     */
    fun feedWatchdog()

    /**
     * Gets the serial communication provider for external peripherals.
     */
    fun getSerialProvider(): ISerialProvider

    /**
     * Gets the GPIO provider for direct relay/IO control.
     */
    fun getGpioProvider(): IGpioProvider

    /**
     * Gets the MDB provider for vending machine communication.
     */
    fun getMdbProvider(): IMdbProvider

    /**
     * Reboots the physical terminal.
     */
    fun reboot()

    /**
     * Checks the physical security status (Tamper).
     * @return true if tampered, false if secure.
     */
    fun getTamperStatus(): Boolean

    /**
     * Releases hardware resources.
     */
    fun release()
}
