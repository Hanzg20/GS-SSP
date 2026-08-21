package com.goldsky.ssp.payment.hardware.wizarpos

import android.util.Log
import com.cloudpos.POSTerminal
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * WizarPOS Payment Provider implementation.
 * Integrates with PAYWizard app via Local Socket (127.0.0.1:6666).
 * Aligned with WIZARPOSPaymentAppIntegrationProtocolV2.3.13 and P3 Protocol.
 */
class WizarPosPaymentProvider(private val terminal: POSTerminal?) : IPaymentProvider {
    
    companion object {
        private const val TAG = "WizarPosPayment"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Data model aligned with WizarPOS GlobalRequest.java
     */
    @Serializable
    private data class GlobalRequest(
        val TransType: String,
        val TransIndexCode: String? = null,
        val TransAmount: String? = null,
        val CallerName: String = "GS-SSP",
        val CurrencyCode: String = "0124", // CAD
        val OriTransIndexCode: String? = null,
        val EnableReceipt: Boolean = true,
        val isPrint: String = "true"
    )

    override fun startSale(amountInCents: Int, ecrRefNum: String, callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "Starting PAYWizard SALE: $amountInCents cents")
        
        CoroutineScope(Dispatchers.Main).launch {
            callback.onProgress("CONNECTING TO TERMINAL...")
            
            val request = GlobalRequest(
                TransType = "Purchase",
                TransAmount = amountInCents.toString(),
                TransIndexCode = ecrRefNum
            )

            executeRequest(request, ecrRefNum, callback)
        }
    }

    override fun voidTransaction(refNum: String, callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "Requesting PAYWizard VOID for $refNum")
        
        CoroutineScope(Dispatchers.Main).launch {
            callback.onProgress("CONNECTING TO TERMINAL...")
            
            val request = GlobalRequest(
                TransType = "Reversal",
                OriTransIndexCode = refNum,
                TransIndexCode = "V-" + java.lang.System.currentTimeMillis()
            )

            executeRequest(request, refNum, callback)
        }
    }

    override fun refundTransaction(refNum: String, amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "Requesting PAYWizard REFUND for $refNum ($amountInCents cents)")
        
        CoroutineScope(Dispatchers.Main).launch {
            callback.onProgress("CONNECTING TO TERMINAL...")
            
            val request = GlobalRequest(
                TransType = "Refund",
                TransAmount = amountInCents.toString(),
                OriTransIndexCode = refNum,
                TransIndexCode = "R-" + java.lang.System.currentTimeMillis()
            )

            executeRequest(request, refNum, callback)
        }
    }

    private suspend fun executeRequest(
        request: GlobalRequest, 
        originalRef: String, 
        callback: IPaymentProvider.PaymentCallback
    ) {
        try {
            // First perform handshake to wake up P3 service if needed.
            // Using CTRL_HANDSHAKE_REQ as recommended in demo.
            val handshakeRes = WizarPosSocketClient.sendRequest("", WizarPosP3Protocol.CTRL_HANDSHAKE_REQ)
            if (handshakeRes == null) {
                Log.w(TAG, "P3 Handshake failed, attempting to proceed anyway...")
            }

            val requestJson = json.encodeToString(request)
            val response = WizarPosSocketClient.sendRequest(requestJson)
            
            if (response != null) {
                val root = json.parseToJsonElement(response).jsonObject
                
                // Response keys in GlobalResponse are often case-sensitive too
                val resultCode = root["ResultCode"]?.jsonPrimitive?.content 
                    ?: root["resultCode"]?.jsonPrimitive?.content
                    
                val resultMsg = root["ResultDesc"]?.jsonPrimitive?.content 
                    ?: root["resultMsg"]?.jsonPrimitive?.content 
                    ?: "Unknown Error"
                
                if (resultCode == "0" || resultCode == "00") {
                    val authNo = root["AuthCode"]?.jsonPrimitive?.content ?: "OK"
                    val refNo = root["RRN"]?.jsonPrimitive?.content ?: originalRef
                    callback.onSuccess(authNo, refNo, "CTLS")
                } else {
                    callback.onFailure("Payment Error: $resultMsg ($resultCode)")
                }
            } else {
                callback.onFailure("Communication Timeout (P3)", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execute error: ${e.message}")
            callback.onFailure("Internal Error: ${e.message}", true)
        }
    }

    override fun startCardDetection(amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        callback.onProgress("READY")
    }

    override fun stopCardDetection() {}

    override fun cancelCurrentTransaction() {
        Log.w(TAG, "Cancellation not supported via synchronous P3 socket integration")
    }

    override fun closeBatch(callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "Requesting PAYWizard SETTLE")
        
        CoroutineScope(Dispatchers.Main).launch {
            val request = GlobalRequest(
                TransType = "Settle",
                TransIndexCode = "S-" + java.lang.System.currentTimeMillis()
            )
            
            executeRequest(request, "SETTLE", callback)
        }
    }
}
