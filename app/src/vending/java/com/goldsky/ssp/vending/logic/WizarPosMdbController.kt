package com.goldsky.ssp.vending.logic

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IMdbProvider
import com.goldsky.ssp.payment.hardware.wizarpos.WizarPosHardwareProvider
import kotlin.math.roundToInt

/**
 * Bridges the WizarPOS Q3mini's event-driven ExtBoardDevice MDB API
 * ([IMdbProvider], implemented by WizarPosMdbProvider in the main source set)
 * to the [IMdbController] contract VendingViewModel already speaks -- the
 * same role [MockMdbController] plays off real hardware. Only meant to be
 * selected when DeviceAdapter.getModel() == WIZARPOS_Q3MINI (see
 * docs/wizarpos_upt_integration_spec.md).
 *
 * CAUTION -- pieces of this mapping are best-effort, not verified against the
 * official MDB interface board protocol PDF or a real device:
 *  - [amount] here is MDBEvent.eventAmount (Float); converting to integer
 *    cents assumes a scale of 2 decimal places (`* 100`), which has not been
 *    confirmed against the board's configured MDBConfig.decimalPlace/scaleFactor.
 *  - [slot] is the VMC's raw numeric item id (MDBEvent.eventItem) turned into
 *    a string, not an "A1"-style planogram label -- InventoryManager's stock
 *    map keys are alphanumeric labels, so a real item-id -> slot-label table
 *    is still needed before inventory lookups here will match real products.
 *  - IMdbProvider.MdbEventListener.onVendFailure() carries no error code, so
 *    the callback below always reports -1; VendingViewModel's
 *    "MDB_FAILURE_$code" audit reason will not be more specific than that
 *    until the interface exposes the VMC's actual failure reason.
 * Validate all of the above on a Q3mini before relying on this for real
 * transactions.
 */
class WizarPosMdbController(context: Context) : IMdbController {

    companion object {
        private const val TAG = "WizarPosMdbCtrl"
    }

    private val hardware =
        HardwareFactory.getHardwareProvider("WIZARPOS") as WizarPosHardwareProvider
    private val mdbProvider: IMdbProvider

    private var vendRequestCallback: ((Int, String) -> Unit)? = null
    private var successCallback: (() -> Unit)? = null
    private var failureCallback: ((Int) -> Unit)? = null

    init {
        hardware.init(context)
        mdbProvider = hardware.getMdbProvider()
    }

    override fun initialize() {
        mdbProvider.startPolling(object : IMdbProvider.MdbEventListener {
            override fun onVendRequest(amount: Float, item: Int) {
                vendRequestCallback?.invoke((amount * 100).roundToInt(), item.toString())
            }

            override fun onVendSuccess() {
                successCallback?.invoke()
            }

            override fun onVendFailure() {
                failureCallback?.invoke(-1)
            }
        })
    }

    override fun onVendRequest(callback: (amount: Int, slot: String) -> Unit) {
        vendRequestCallback = callback
    }

    override fun onVendSuccess(callback: () -> Unit) {
        successCallback = callback
    }

    override fun onVendFailure(callback: (errorCode: Int) -> Unit) {
        failureCallback = callback
    }

    override fun approveVend() {
        if (!mdbProvider.approveVend()) {
            Log.e(TAG, "approveVend() failed -- no pending request or ExtBoard error")
        }
    }

    override fun denyVend() {
        if (!mdbProvider.denyVend()) {
            Log.e(TAG, "denyVend() failed -- no pending request or ExtBoard error")
        }
    }

    override fun setFailNext(fail: Boolean) {
        Log.w(TAG, "setFailNext() is a MockMdbController-only simulation hook; ignored on real WizarPOS hardware")
    }
}
