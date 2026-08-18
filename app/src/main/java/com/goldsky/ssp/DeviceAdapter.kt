package com.goldsky.ssp

import android.os.Build

/**
 * Detects hardware model and capabilities.
 */
object DeviceAdapter {

    enum class HardwareModel {
        IM25,
        IM30,
        WIZARPOS_Q1,
        WIZARPOS_Q2,
        UNKNOWN
    }

    /**
     * Detects the hardware model based on Build.MODEL.
     */
    fun getModel(): HardwareModel {
        val model = Build.MODEL.uppercase()
        return when {
            model.contains("IM25") -> HardwareModel.IM25
            model.contains("IM30") -> HardwareModel.IM30
            model.contains("WIZARPOS") && model.contains("Q1") -> HardwareModel.WIZARPOS_Q1
            model.contains("WIZARPOS") && model.contains("Q2") -> HardwareModel.WIZARPOS_Q2
            else -> HardwareModel.UNKNOWN
        }
    }

    /**
     * Returns true if the device has a full screen suitable for rich UI.
     */
    fun isRichUiSupported(): Boolean = getModel() == HardwareModel.IM30 || getModel() == HardwareModel.WIZARPOS_Q2

    /**
     * Returns true if the device is a smaller footprint terminal.
     */
    fun isCompactTerminal(): Boolean = getModel() == HardwareModel.IM25 || getModel() == HardwareModel.WIZARPOS_Q1
}
