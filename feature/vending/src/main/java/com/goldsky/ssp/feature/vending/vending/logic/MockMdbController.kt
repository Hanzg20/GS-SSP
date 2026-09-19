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
    private var failNext: Boolean = false

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

    override fun setFailNext(fail: Boolean) {
        this.failNext = fail
    }

    override fun approveVend() {
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
