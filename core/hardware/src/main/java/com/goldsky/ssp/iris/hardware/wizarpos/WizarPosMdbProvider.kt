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
        /** ISO 4217 numeric (ExtBoardDevice MDB guide: "840 for USD"); the board reads it back as 0x1124. */
        private const val CURRENCY_CAD = 124
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
                // WizarPOS (2026-11-02), as in their demo: with the check on, a VMC whose SETUP /
                // CONFIG handshake is out of the usual order gets Begin Session and vends blocked.
                runCatching { extBoardDevice?.setConfigStateCheck(false) }
                    .onFailure { Log.w(TAG, "setConfigStateCheck: ${it.message}") }
                // Always Idle is on: the board must never open a session by itself (it would offer
                // funds with no card). WizarPOS: with Always Idle, auto Begin Session off.
                runCatching { extBoardDevice?.enableAutoBeginSession(0, 0f) }
                    .onFailure { Log.w(TAG, "enableAutoBeginSession: ${it.message}") }
                setCurrencyCad()
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
     * The board came set to USD (reads 0x1840); the machines here are Canadian. Everything
     * else in the config (level, decimals, scale) is kept as the board has it.
     */
    private fun setCurrencyCad() {
        val d = extBoardDevice ?: return
        runCatching {
            val c = d.config
            if (c.currencyCode != CURRENCY_CAD && c.currencyCode != 0x1124) {
                Log.i(TAG, "MDB currency 0x${Integer.toHexString(c.currencyCode)} -> CAD")
                c.currencyCode = CURRENCY_CAD
                d.setConfig(c)
            }
        }.onFailure { Log.w(TAG, "Set currency CAD: ${it.message}") }
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
            // The machine cancelled: answer it the way WizarPOS's demo does (VEND_CANCEL, result 0).
            MDBEvent.TYPE_VEND_CANCEL -> {
                pendingVendEvent = null
                send(MDBEvent().apply { eventType = MDBEvent.TYPE_VEND_CANCEL; eventResult = 0 })
                listener.onVendCancel()
            }
            // The board asks for Begin Session when the machine isn't Always Idle; our card-first
            // flow sends it once the hold is approved, so nothing to do here yet.
            MDBEvent.TYPE_TRANSACTION_START -> Log.i(TAG, "Transaction start from the board")
        }
    }

    /**
     * Approves the pending vend request.
     */
    override fun approveVend(): Boolean = respondToPendingVend(MDBEvent.TYPE_VEND_APPROVED, 1)

    /** Denies the pending vend request. */
    override fun denyVend(): Boolean = respondToPendingVend(MDBEvent.TYPE_VEND_DENIED, -1)

    // A fresh event with result 1 / -1 and the requested amount, as WizarPOS's MDBTestForCloudSDK does.
    private fun respondToPendingVend(responseType: Int, result: Int): Boolean {
        val event = pendingVendEvent
        if (event == null) {
            Log.w(TAG, "respondToPendingVend($responseType) called with no pending vend request")
            return false
        }
        pendingVendEvent = null
        return try {
            extBoardDevice?.respondEvent(MDBEvent().apply {
                eventType = responseType
                eventResult = result
                eventAmount = if (result == 1) event.eventAmount else 0f
            })
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

    // Begin session: BEGIN_SESSION with the funds, as WizarPOS's demo sends on TRANSACTION_START.
    // End session: VEND_CANCEL -- WizarPOS (2026-11-02): the board sends Session Cancel to the
    // VMC and both go back to idle.
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
