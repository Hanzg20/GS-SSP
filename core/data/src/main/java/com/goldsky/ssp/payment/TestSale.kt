package com.goldsky.ssp.payment

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Technician "small real test" (2026-10-06): the next card sale runs the
 * complete customer flow -- package, card, the machine actually running,
 * transaction record, settlement, automatic reversal on a start failure --
 * but charges [Armed.cents] instead of the package price. The machine still
 * gets the full package (wash pulses / timer time follow the package, not the
 * charged amount), so what is tested is exactly what a customer gets.
 *
 * Armed from the technician panel for ONE sale; disarms itself after that
 * sale or after [ARM_MS]. Process memory only, so an app restart disarms it.
 * Sales are recorded with [REF_PREFIX] so CMP can tell them apart.
 */
object TestSale {
    const val REF_PREFIX = "TEST_"
    val AMOUNTS = listOf(10, 50, 100)
    private const val ARM_MS = 10 * 60_000L
    private const val TAG = "TestSale"

    data class Armed(val cents: Int)

    private val _armed = MutableStateFlow<Armed?>(null)
    val armed: StateFlow<Armed?> = _armed.asStateFlow()

    private var expiry: Job? = null

    fun arm(cents: Int) {
        _armed.value = Armed(cents)
        expiry?.cancel()
        expiry = CoroutineScope(Dispatchers.Default).launch {
            delay(ARM_MS)
            if (_armed.value != null) Log.i(TAG, "Test sale expired unused")
            _armed.value = null
        }
        Log.i(TAG, "Armed: next card sale charges $cents cents")
    }

    fun disarm() {
        expiry?.cancel()
        _armed.value = null
    }

    /** What the next card sale would charge, without using it up. */
    fun peek(): Int? = _armed.value?.cents

    /** Takes the armed amount for this sale (null = a normal sale). */
    fun consume(): Int? = _armed.value?.cents?.also {
        disarm()
        Log.i(TAG, "Test sale in progress: charging $it cents")
    }

    fun isTestRef(ecrRefNum: String?) = ecrRefNum?.startsWith(REF_PREFIX) == true

    fun format(cents: Int) = "$%d.%02d".format(cents / 100, cents % 100)
}
