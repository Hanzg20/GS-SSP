package com.goldsky.ssp.payment.hardware.pax

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.IMdbProvider
import kotlinx.coroutines.*
import pax.util.MDBManager

/**
 * PAX implementation of IMdbProvider using UPTAPI MDBManager.
 * Note: PAX MDB API is lower-level than WizarPOS, requiring raw buffer management.
 */
class PaxMdbProvider(private val context: Context) : IMdbProvider {
    
    companion object {
        private const val TAG = "PaxMdb"
        private const val MDB_PATH = "/dev/ttyS1" // Typical MDB port on IM30
    }
    
    private var mdbManager: MDBManager? = null
    private var portHandle: Int = -1
    private var pollJob: Job? = null

    private fun ensureOpened(): Boolean {
        if (portHandle != -1) return true
        return try {
            mdbManager = MDBManager(context)
            portHandle = mdbManager?.mdbOpen(MDB_PATH) ?: -1
            if (portHandle != -1) {
                // Set to Peripheral (Slave) mode by default for Vending Cashless
                mdbManager?.mdbSetMode(portHandle, MDBManager.MDB_PERIPHERAL)
                Log.i(TAG, "PAX MDB port opened: $MDB_PATH (handle: $portHandle)")
                true
            } else {
                Log.e(TAG, "Failed to open PAX MDB port")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "MDB Open exception: ${e.message}")
            false
        }
    }

    override fun startPolling(listener: IMdbProvider.MdbEventListener) {
        if (!ensureOpened()) return

        pollJob?.cancel()
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "PAX MDB Poll Loop Started")
            val buffer = ShortArray(256)
            while (isActive) {
                try {
                    // Raw read from MDB bus. This would require parsing the MDB protocol 
                    // (Setup, Enable, Vend, etc.) manually or via a helper.
                    val readLen = mdbManager?.mdbRead(portHandle, buffer, 256, 0, 0, 100) ?: -1
                    if (readLen > 0) {
                        // Protocol parsing logic goes here
                        // For prototype, we'll log the raw hit
                        Log.d(TAG, "MDB Data received: $readLen shorts")
                    }
                    delay(50)
                } catch (e: Exception) {
                    Log.w(TAG, "MDB Poll error: ${e.message}")
                }
            }
        }
    }

    override fun stopPolling() {
        pollJob?.cancel()
        if (portHandle != -1) {
            mdbManager?.mdbClose(portHandle)
            portHandle = -1
        }
        mdbManager = null
    }

    override fun approveVend(): Boolean {
        // Implementation for PAX would involve mdbWrite with VEND APPROVED bytes
        return false
    }

    override fun denyVend(): Boolean {
        // Implementation for PAX would involve mdbWrite with VEND DENIED bytes
        return false
    }
}
