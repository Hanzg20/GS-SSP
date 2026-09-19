package com.goldsky.ssp.payment

import android.content.Context

/**
 * Single source of truth for whether the terminal is allowed to take
 * payments. Locked either by an admin disabling the device
 * (devices.is_active, checked via DeviceRepository.syncDeviceIdentity) or by
 * an explicit remote LOCK command (RemoteCommandManager). The remote-lock
 * flag is persisted so it survives an app/process restart until explicitly
 * cleared by an UNLOCK command.
 */
object DeviceAccessManager {
    private const val PREFS_NAME = "device_access_prefs"
    private const val KEY_REMOTE_LOCKED = "remote_locked"

    @Volatile private var appContext: Context? = null
    @Volatile private var remoteLocked = false
    @Volatile private var deviceActive = true // optimistic until proven otherwise

    fun init(context: Context) {
        appContext = context.applicationContext
        remoteLocked = prefs()?.getBoolean(KEY_REMOTE_LOCKED, false) ?: false
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLocked(): Boolean = remoteLocked || !deviceActive

    fun lockReason(): String? = when {
        remoteLocked && !deviceActive -> "Remote locked & disabled by admin"
        remoteLocked -> "Remote locked by operator"
        !deviceActive -> "Device disabled by admin"
        else -> null
    }

    fun setRemoteLock(locked: Boolean) {
        remoteLocked = locked
        prefs()?.edit()?.putBoolean(KEY_REMOTE_LOCKED, locked)?.apply()
    }

    /**
     * Feed the is_active result of DeviceRepository.syncDeviceIdentity() here. Pass null
     * (network failure / unknown) to intentionally leave the current state
     * untouched -- a connectivity blip must never lock out a legitimate device.
     */
    fun applyActiveState(isActive: Boolean?) {
        if (isActive != null) deviceActive = isActive
    }
}
