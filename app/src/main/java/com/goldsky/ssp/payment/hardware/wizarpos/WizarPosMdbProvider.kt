package com.goldsky.ssp.payment.hardware.wizarpos

import android.util.Log
import com.cloudpos.POSTerminal
import com.cloudpos.extboard.ExtBoardDevice
import com.cloudpos.extboard.bean.MDBEvent
import com.goldsky.ssp.payment.hardware.IMdbProvider
import kotlinx.coroutines.*

/**
 * High-level MDB Provider for WizarPOS Q3mini UPT.
 * Uses the officially recommended ExtBoardDevice.pollEvent mechanism.
 */
class WizarPosMdbProvider(private val terminal: POSTerminal?) : IMdbProvider {
    
    companion object {
        private const val TAG = "WizarPosMdb"
    }
    
    private var extBoardDevice: ExtBoardDevice? = null
    private var pollJob: Job? = null
    private var pendingVendEvent: MDBEvent? = null

    private fun ensureOpened(): Boolean {
        if (extBoardDevice == null) {
            try {
                extBoardDevice = terminal?.getDevice("com.cloudpos.device.extboard") as? ExtBoardDevice
                extBoardDevice?.open()
                Log.i(TAG, "ExtBoard opened for MDB")
            } catch (e: Exception) {
                Log.e(TAG, "MDB Open failed: ${e.message}")
                return false
            }
        }
        return extBoardDevice != null
    }

    /**
     * Starts the event loop to listen for MDB bus events.
     */
    override fun startPolling(listener: IMdbProvider.MdbEventListener) {
        if (!ensureOpened()) return
        
        pollJob?.cancel()
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "MDB Poll Loop Started")
            while (isActive) {
                try {
                    val event = extBoardDevice?.pollEvent(5000) // 5s block
                    if (event != null) {
                        handleEvent(event, listener)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: ${e.message}")
                }
            }
        }
    }

    private fun handleEvent(event: MDBEvent, listener: IMdbProvider.MdbEventListener) {
        Log.d(TAG, "MDB Event received: type=${event.eventType}")
        when (event.eventType) {
            MDBEvent.TYPE_VEND_REQUEST -> {
                pendingVendEvent = event
                listener.onVendRequest(event.eventAmount, event.eventItem)
            }
            MDBEvent.TYPE_VEND_SUCCESS -> {
                pendingVendEvent = null
                listener.onVendSuccess()
            }
            MDBEvent.TYPE_VEND_FAILURE -> {
                pendingVendEvent = null
                listener.onVendFailure()
            }
        }
    }

    /**
     * Approves the pending vend request.
     */
    override fun approveVend(): Boolean = respondToPendingVend(MDBEvent.TYPE_VEND_APPROVED)

    /** Denies the pending vend request. */
    override fun denyVend(): Boolean = respondToPendingVend(MDBEvent.TYPE_VEND_DENIED)

    private fun respondToPendingVend(responseType: Int): Boolean {
        val event = pendingVendEvent
        if (event == null) {
            Log.w(TAG, "respondToPendingVend($responseType) called with no pending vend request")
            return false
        }
        pendingVendEvent = null
        return try {
            event.eventType = responseType
            extBoardDevice?.respondEvent(event)
            true
        } catch (e: Exception) {
            Log.e(TAG, "respondEvent($responseType) failed: ${e.message}")
            false
        }
    }

    override fun stopPolling() {
        pollJob?.cancel()
        pendingVendEvent = null
        try {
            extBoardDevice?.close()
        } catch (e: Exception) {
            // Ignore
        }
        extBoardDevice = null
    }
}
