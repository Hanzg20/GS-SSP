package com.goldsky.ssp.iris.hardware.pax

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.IMdbProvider
import kotlinx.coroutines.*
import pax.util.MDBManager

/**
 * PAX implementation of IMdbProvider using UPTAPI MDBManager.
 */
class PaxMdbProvider(private val context: Context) : IMdbProvider {
    
    companion object {
        private const val TAG = "PaxMdb"
        private const val MDB_PATH = "/dev/ttyS1" 
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
                    val readLen = mdbManager?.mdbRead(portHandle, buffer, 256, 0, 0, 100) ?: -1
                    if (readLen > 0) {
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
        return false
    }

    override fun denyVend(): Boolean {
        return false
    }
}
