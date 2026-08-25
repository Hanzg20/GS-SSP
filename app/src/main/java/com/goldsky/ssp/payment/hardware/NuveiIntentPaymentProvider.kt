package com.goldsky.ssp.payment.hardware

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.goldsky.ssp.payment.DeviceRepository

/**
 * Nuvei Path C implementation using Android Intent API.
 * Designed for handheld terminals like WizarPOS Q2.
 */
class NuveiIntentPaymentProvider(private val context: Context) : IPaymentProvider {
    
    companion object {
        private const val TAG = "NuveiIntentPayment"
        private const val NUVEI_ACTION = "com.nuvei.pos.TRANSACTION"
        private const val NUVEI_PACKAGE = "com.nuvei.pos.app"
    }

    private var resultLauncher: ActivityResultLauncher<Intent>? = null
    private var activeCallback: IPaymentProvider.PaymentCallback? = null

    /**
     * Connects the Activity result launcher to this provider.
     */
    fun setResultLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.resultLauncher = launcher
    }

    /**
     * Handles the Intent result returned from the Nuvei App.
     */
    fun handleActivityResult(resultCode: Int, data: Intent?) {
        val callback = activeCallback ?: return
        if (data == null) {
            callback.onFailure("No response from payment app")
            return
        }

        val status = data.getStringExtra("status") // SUCCESS, DECLINED, ERROR, CANCELLED
        val txId = data.getStringExtra("transactionId") ?: "N/A"
        val authCode = data.getStringExtra("authCode") ?: ""

        when (status) {
            "SUCCESS" -> callback.onSuccess(authCode, txId, "EMV_OR_CTLS")
            "DECLINED" -> callback.onFailure("Card Declined")
            "CANCELLED" -> callback.onFailure("Transaction Cancelled")
            else -> callback.onFailure(data.getStringExtra("message") ?: "Unknown Error")
        }
        activeCallback = null
    }

    override fun startSale(amountInCents: Int, ecrRefNum: String, callback: IPaymentProvider.PaymentCallback) {
        val launcher = resultLauncher
        if (launcher == null) {
            callback.onFailure("Internal Error: Result launcher not attached", true)
            return
        }

        activeCallback = callback
        callback.onProgress("Invoking Nuvei Payment...")

        val intent = Intent(NUVEI_ACTION).apply {
            setPackage(NUVEI_PACKAGE)
            putExtra("transactionType", "Sale")
            putExtra("amount", "%.2f".format(amountInCents / 100.0))
            putExtra("currency", "USD")
            putExtra("merchantId", "MOCK_MERCHANT_ID") // Should come from DeviceRepository
            putExtra("merchantSiteId", "MOCK_SITE_ID")
            putExtra("clientUniqueId", ecrRefNum)
            putExtra("printReceipt", "Both")
            putExtra("getReceipt", true)
        }

        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Nuvei Intent: ${e.message}")
            callback.onFailure("Nuvei Payment App not found", true)
        }
    }

    override fun voidTransaction(refNum: String, callback: IPaymentProvider.PaymentCallback) {
        // Build Void Intent...
    }

    override fun refundTransaction(refNum: String, amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        // Build Refund Intent...
    }

    override fun startCardDetection(amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        callback.onSuccess("READY", "MOCK_DETECTION")
    }

    override fun stopCardDetection() {}

    override fun cancelCurrentTransaction() {}

    override fun closeBatch(callback: IPaymentProvider.PaymentCallback) {
        // Build Settlement Intent...
    }
}
