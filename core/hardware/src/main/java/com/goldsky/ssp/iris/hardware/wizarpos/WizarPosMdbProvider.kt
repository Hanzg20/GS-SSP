package com.goldsky.ssp.iris.hardware.wizarpos

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
                logBoardState()
            } catch (e: Throwable) {
                // Throwable: a missing native lib is an UnsatisfiedLinkError, not an Exception.
                Log.e(TAG, "MDB Open failed: ${e.message}")
                extBoardDevice = null
                return false
            }
        }
        return extBoardDevice != null
    }

    /**
     * What the board is set to and whether a vending machine is on the bus -- read only,
     * for the log: the amount scale (decimalPlace / scaleFactor) and Always Idle decide how
     * a VMC's prices and sessions look to us, and haven't been checked on a real machine.
     */
    private fun logBoardState() {
        val d = extBoardDevice ?: return
        runCatching { Log.i(TAG, "Board version: ${d.boardVersion}") }.onFailure { Log.w(TAG, "getBoardVersion: ${it.message}") }
        runCatching {
            val c = d.config
            Log.i(TAG, "MDB config: level=${c.level} currency=${c.currencyCode} decimals=${c.decimalPlace} scale=${c.scaleFactor}")
        }.onFailure { Log.w(TAG, "getConfig: ${it.message}") }
        runCatching {
            val o = d.option
            Log.i(TAG, "MDB options: alwaysIdle=${o.enableAlwaysIdle} expandedCurrency=${o.enableExpandedCurrency} " +
                "remoteVend=${o.enableRemoteVend} negativeVend=${o.enableNegativeVend}")
        }.onFailure { Log.w(TAG, "getOption: ${it.message}") }
        runCatching { Log.i(TAG, "MDB bus: ${if (d.mdbConnStatus == 1) "online (VMC present)" else "offline (no VMC)"}") }
            .onFailure { Log.w(TAG, "getMDBConnStatus: ${it.message}") }
    }

    /**
     * Starts the event loop to listen for MDB bus events.
     */
    override fun startPolling(listener: IMdbProvider.MdbEventListener) {
        if (!ensureOpened()) return
        
        // pollEvent answers "-1 Operation not permitted" at once until the board's MDB
        // loop runs (seen on 255, 2026-11-02, before this call existed).
        try {
            extBoardDevice?.startLoop()
            Log.i(TAG, "MDB loop started")
        } catch (e: Exception) {
            Log.e(TAG, "startLoop failed: ${e.message}")
        }
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
                    // -110 = the 5 s wait ended with no event: normal, poll again.
                    if (e.message?.contains("-110") == true) continue
                    // Any other error returns at once: back off, or the loop spins a core.
                    Log.w(TAG, "Poll error: ${e.message}")
                    delay(2000)
                }
            }
        }
    }

    private fun handleEvent(event: MDBEvent, listener: IMdbProvider.MdbEventListener) {
        Log.i(TAG, "MDB event: type=${event.eventType} result=${event.eventResult} amount=${event.eventAmount} item=${event.eventItem} count=${event.eventItemCount}")
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

    override fun isOpen(): Boolean = extBoardDevice != null

    override fun describe(): String {
        val d = extBoardDevice ?: return "MDB board not open"
        val parts = mutableListOf<String>()
        runCatching { parts += "board v${d.boardVersion}" }
        runCatching { d.config.let { parts += "level ${it.level}, ${it.decimalPlace} decimals, currency 0x${Integer.toHexString(it.currencyCode)}" } }
        runCatching { parts += if (d.option.enableAlwaysIdle) "always idle" else "sessions" }
        parts += when (busOnline()) {
            true -> "machine ONLINE"
            false -> "no machine on the bus"
            null -> "bus status unknown"
        }
        return parts.joinToString(" | ")
    }

    override fun busOnline(): Boolean? = try {
        extBoardDevice?.mdbConnStatus?.let { it == 1 }
    } catch (e: Exception) {
        Log.w(TAG, "getMDBConnStatus: ${e.message}")
        null
    }

    // Begin / end session: host-sent events, by the SDK's event types (MDBEvent.TYPE_BEGIN_SESSION /
    // TYPE_VEND_CANCEL). The javadoc names them but doesn't describe this exchange -- unverified
    // until a real VMC is on the bus; ask WizarPOS for the cashless session sequence.
    override fun beginSession(funds: Float): Boolean = send(MDBEvent().apply {
        eventType = MDBEvent.TYPE_BEGIN_SESSION
        eventAmount = funds
    })

    override fun endSession(): Boolean = send(MDBEvent().apply { eventType = MDBEvent.TYPE_VEND_CANCEL })

    private fun send(event: MDBEvent): Boolean {
        if (!ensureOpened()) return false
        return try {
            extBoardDevice?.respondEvent(event)
            Log.i(TAG, "Sent MDB event type=${event.eventType} amount=${event.eventAmount}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "respondEvent(type=${event.eventType}) failed: ${e.message}")
            false
        }
    }

    override fun stopPolling() {
        pollJob?.cancel()
        runCatching { extBoardDevice?.stopLoop() }
        pendingVendEvent = null
        try {
            extBoardDevice?.close()
        } catch (e: Exception) {
            // Ignore
        }
        extBoardDevice = null
    }
}
