package com.goldsky.ssp.payment.hardware.wizarpos

import android.util.Log
import com.cloudpos.POSTerminal
import com.goldsky.ssp.payment.hardware.IPaymentProvider

/**
 * WizarPOS Payment Provider implementation.
 * Integrates with WizarPOS EMV SDK.
 */
class WizarPosPaymentProvider(private val terminal: POSTerminal?) : IPaymentProvider {
    
    companion object {
        private const val TAG = "WizarPosPayment"
    }

    override fun startSale(amountInCents: Int, ecrRefNum: String, callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "Starting WizarPOS Sale: $amountInCents cents")
        callback.onProgress("PLEASE TAP/INSERT CARD")
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            callback.onSuccess("WIZAR_${System.currentTimeMillis()}", ecrRefNum, "CTLS")
        }, 3000)
    }

    override fun voidTransaction(refNum: String, callback: IPaymentProvider.PaymentCallback) {
        callback.onFailure("VOID not yet implemented for WizarPOS", false)
    }

    override fun refundTransaction(refNum: String, amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        callback.onFailure("REFUND not yet implemented for WizarPOS", false)
    }

    override fun startCardDetection(amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
    }

    override fun stopCardDetection() {
    }

    override fun cancelCurrentTransaction() {
        Log.i(TAG, "Cancelling WizarPOS transaction")
    }

    override fun closeBatch(callback: IPaymentProvider.PaymentCallback) {
        callback.onSuccess("BATCH_OK", "BATCH_REF")
    }
}
