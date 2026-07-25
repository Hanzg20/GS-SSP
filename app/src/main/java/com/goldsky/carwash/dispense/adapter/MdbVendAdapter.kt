package com.goldsky.carwash.dispense.adapter

import android.util.Log
import com.goldsky.carwash.dispense.DispenseJob
import com.goldsky.carwash.dispense.DispenseOutcome
import com.goldsky.carwash.dispense.IAckStrategy
import com.goldsky.carwash.dispense.IDispenseAdapter

/**
 * Placeholder for real vending machines (MDB bus). Deliberately not
 * implemented: MDB is a different electrical/timing protocol from the plain
 * hex-over-RS232 frames [com.pax.dal.IUart]/[com.goldsky.carwash.serial.SerialPortManager]
 * speak, not just a different hex table -- it needs its own transport driver
 * (MDB master board + real driver) before this can do anything. Wired into
 * the registry now so `dispense_protocol = "mdb_vend"` fails loudly and
 * traceably instead of silently falling back to the wrong adapter.
 */
class MdbVendAdapter : IDispenseAdapter {
    override suspend fun dispense(job: DispenseJob, ackStrategy: IAckStrategy): DispenseOutcome {
        Log.e(TAG, "MDB vending protocol requested but not implemented -- needs a real MDB transport driver")
        return DispenseOutcome.Failed("mdb_not_implemented")
    }

    private companion object {
        const val TAG = "MdbVendAdapter"
    }
}
