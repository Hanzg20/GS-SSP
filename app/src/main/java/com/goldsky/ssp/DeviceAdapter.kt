package com.goldsky.ssp

import android.os.Build

/**
 * Detects hardware model and capabilities.
 */
object DeviceAdapter {

    enum class HardwareModel {
        IM25,
        IM30,
        UNKNOWN
    }

    /**
     * Detects the PAX model based on Build.MODEL.
     */
    fun getModel(): HardwareModel {
        val model = Build.MODEL.uppercase()
        return when {
            model.contains("IM25") -> HardwareModel.IM25
            model.contains("IM30") -> HardwareModel.IM30
            else -> HardwareModel.UNKNOWN
        }
    }

    /**
     * Returns true if the device has a full screen suitable for rich UI (IM30).
     */
    fun isRichUiSupported(): Boolean = getModel() == HardwareModel.IM30

    /**
     * Returns true if the device is a smaller footprint terminal (IM25).
     */
    fun isCompactTerminal(): Boolean = getModel() == HardwareModel.IM25
}
