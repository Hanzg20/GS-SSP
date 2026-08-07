package com.goldsky.carwash.payment.hardware.pax

import android.content.Context
import android.util.Log
import com.goldsky.carwash.payment.hardware.IPaymentProvider
import com.pax.poslink.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PAX implementation of IPaymentProvider.
 * Wraps POSLink for SALE, VOID, and REFUND operations.
 * Supports dynamic switching between AIDL (Real Hardware) and TCP (Windows Simulator).
 */
class PaxPaymentProvider(private val context: Context) : IPaymentProvider {
    private val TAG = "PaxPayment"

    /**
     * Configuration for PAX Communication.
     * In a production environment, this would come from ConfigManager/Cloud.
     */
    data class PaxConfig(
        val commType: String = "AIDL", // "AIDL" or "TCP"
        val destIP: String = "127.0.0.1",
        val destPort: String = "10009",
        val timeout: String = "60000"
    )

    private var activeConfig = PaxConfig()

    fun updateConfig(config: PaxConfig) {
        activeConfig = config
        Log.i(TAG, "PAX Comm Config updated: ${config.commType} @ ${config.destIP}")
    }

    private fun getCommSetting(): CommSetting {
        return CommSetting().apply {
            commType = activeConfig.commType
            destIP = activeConfig.destIP
            destPort = activeConfig.destPort
            timeout = activeConfig.timeout
        }
    }

    override fun startSale(amountInCents: Int, ecrRefNum: String, callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "Initiating PAX SALE: $amountInCents cents, Ref: $ecrRefNum")
        
        val posLink = PosLink()
        posLink.commSetting = getCommSetting()
        
        val request = PaymentRequest().apply {
            transType = 2 // SALE
            tenderType = 1 // CREDIT
            amount = amountInCents.toString()
            this.ecrRefNum = ecrRefNum
        }
        posLink.paymentRequest = request

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    val response = posLink.paymentResponse
                    if (result != null && result.code == ProcessTransResult.ProcessTransExitCode.OK) {
                        if (response != null && response.resultCode == "000000") {
                            callback.onSuccess(response.authCode ?: "", response.refNum ?: "")
                        } else {
                            callback.onFailure(response?.resultMsg ?: "Declined")
                        }
                    } else {
                        callback.onFailure(result?.msg ?: "SDK Error")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onFailure(e.message ?: "Unknown Error")
                }
            }
        }
    }

    override fun voidTransaction(refNum: String, callback: IPaymentProvider.PaymentCallback) {
        Log.w(TAG, "Initiating PAX VOID: $refNum")
        val posLink = PosLink()
        posLink.commSetting = getCommSetting()
        
        val request = PaymentRequest().apply {
            transType = 4 // VOID
            origRefNum = refNum
            ecrRefNum = "V" + System.currentTimeMillis()
        }
        posLink.paymentRequest = request

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    if (result != null && result.code == ProcessTransResult.ProcessTransExitCode.OK && posLink.paymentResponse?.resultCode == "000000") {
                        callback.onSuccess("VOID_OK", posLink.paymentResponse.refNum ?: "")
                    } else {
                        callback.onFailure(posLink.paymentResponse?.resultMsg ?: "VOID Failed")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onFailure(e.message ?: "VOID Error") }
            }
        }
    }

    override fun refundTransaction(refNum: String, amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        Log.w(TAG, "Initiating PAX REFUND: $refNum, Amount: $amountInCents")
        val posLink = PosLink()
        posLink.commSetting = getCommSetting()
        
        val request = PaymentRequest().apply {
            transType = 5 // REFUND
            origRefNum = refNum
            amount = amountInCents.toString()
            ecrRefNum = "R" + System.currentTimeMillis()
        }
        posLink.paymentRequest = request

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    if (result != null && result.code == ProcessTransResult.ProcessTransExitCode.OK && posLink.paymentResponse?.resultCode == "000000") {
                        callback.onSuccess("REFUND_OK", posLink.paymentResponse.refNum ?: "")
                    } else {
                        callback.onFailure(posLink.paymentResponse?.resultMsg ?: "REFUND Failed")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onFailure(e.message ?: "REFUND Error") }
            }
        }
    }

    override fun startCardDetection(amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        // PAX POSLink usually manages the "Tap Card" UI and detection internally during ProcessTrans().
        // For pre-detection (e.g. VIP check), we use IPicc from PaxHardwareProvider.
        Log.i(TAG, "startCardDetection: PAX logic relies on DAL/IPicc for pre-auth detection")
    }

    override fun stopCardDetection() {
        // Handled by Dal
    }

    override fun cancelCurrentTransaction() {
        // POSLink cancellation is typically handled via terminal hardware key 
        // or by letting ProcessTrans time out.
    }
}
