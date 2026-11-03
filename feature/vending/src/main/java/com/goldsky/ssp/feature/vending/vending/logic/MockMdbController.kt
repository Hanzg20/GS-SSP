package com.goldsky.ssp.vending.logic

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Simulator for MDB hardware events.
 * Allows development without a physical Vending Machine.
 */
class MockMdbController : IMdbController {
    private var vendRequestCallback: ((Int, String) -> Unit)? = null
    private var successCallback: (() -> Unit)? = null
    private var failureCallback: ((Int) -> Unit)? = null
    private var cancelCallback: (() -> Unit)? = null
    private var failNext: Boolean = false
    /** The next approved vend gets no answer at all (the terminal's 30 s timeout gives the money back). */
    var silentNext: Boolean = false

    override fun initialize() {
        Log.d("MockMdb", "MDB Link established (Simulated)")
    }

    override fun onVendRequest(callback: (Int, String) -> Unit) {
        this.vendRequestCallback = callback
    }

    override fun onVendSuccess(callback: () -> Unit) {
        this.successCallback = callback
    }

    override fun onVendFailure(callback: (Int) -> Unit) {
        this.failureCallback = callback
    }

    override fun onMachineCancel(callback: () -> Unit) {
        this.cancelCallback = callback
    }

    /** The customer pressed cancel on the machine. */
    fun machineCancel() {
        Log.d("MockMdb", "VMC: cancel pressed on the machine (Simulated)")
        cancelCallback?.invoke()
    }

    override fun setFailNext(fail: Boolean) {
        this.failNext = fail
    }

    override fun approveVend() {
        if (silentNext) {
            silentNext = false
            Log.w("MockMdb", "VMC: approved, but saying nothing (Simulated)")
            return
        }
        Log.d("MockMdb", "VMC: Received Approve. Starting motors...")
        // Simulate mechanical delay then success or failure
        CoroutineScope(Dispatchers.Main).launch {
            delay(2000)
            if (failNext) {
                failNext = false
                Log.e("MockMdb", "VMC: Mechanical Jam! (Simulated)")
                failureCallback?.invoke(1) // 1 = Jam
            } else {
                successCallback?.invoke()
            }
        }
    }

    override fun beginSession(fundsCents: Int) {
        Log.d("MockMdb", "VMC: session open, funds $fundsCents cents")
    }

    override fun endSession() {
        Log.d("MockMdb", "VMC: session closed")
    }

    override fun denyVend() {
        Log.d("MockMdb", "VMC: Received Deny. Transaction cancelled.")
    }

    /**
     * Development helper to trigger a fake request from the machine.
     */
    fun simulateMachineSelection(priceCents: Int, slot: String) {
        Log.d("MockMdb", "Machine: User selected $slot for $priceCents cents")
        vendRequestCallback?.invoke(priceCents, slot)
    }
}
