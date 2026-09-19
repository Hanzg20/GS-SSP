package com.goldsky.ssp.iris.hardware.pax

import android.content.Context
import android.util.Log
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import com.pax.poslink.*
import com.pax.poslink.exceptions.PiccException
import com.pax.poslink.peripheries.PiccManager
import kotlinx.coroutines.*

/**
 * PAX implementation of IPaymentProvider.
 */
class PaxPaymentProvider(
    private val context: Context
) : IPaymentProvider {
    private val TAG = "PaxPayment"
    private var detectionJob: Job? = null

    data class PaxConfig(
        val commType: String = if (android.os.Build.PRODUCT.contains("sdk") || android.os.Build.MODEL.contains("Emulator")) "TCP" else "AIDL",
        val destIP: String = if (android.os.Build.PRODUCT.contains("sdk") || android.os.Build.MODEL.contains("Emulator")) "10.0.2.2" else "127.0.0.1",
        val destPort: String = "10009",
        val timeout: String = "60000"
    )

    private var activeConfig = PaxConfig()

    @Volatile
    private var activePosLink: PosLink? = null

    fun updateConfig(config: PaxConfig) {
        val modelName = android.os.Build.MODEL.uppercase()
        val isEmulator = modelName.contains("SDK") || modelName.contains("EMULATOR")
        
        if (config.commType == "TCP" && isEmulator == false) {
            Log.e(TAG, "REJECTED: Attempted to set TCP comm mode on real PAX hardware ($modelName)")
            return
        }
        
        activeConfig = config
        Log.i(TAG, "PAX Comm Config updated: ${config.commType} @ ${config.destIP}")
    }

    private fun getCommSetting(): CommSetting {
        val setting = CommSetting()
        setting.setType(activeConfig.commType)
        setting.setDestIP(activeConfig.destIP)
        setting.setDestPort(activeConfig.destPort)
        setting.setTimeOut(activeConfig.timeout)
        return setting
    }

    override fun startSale(amountInCents: Int, ecrRefNum: String, callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "Initiating PAX SALE: $amountInCents cents, Ref: $ecrRefNum")

        val posLink = PosLink()
        posLink.SetCommSetting(getCommSetting())
        activePosLink = posLink

        val request = PaymentRequest()
        request.TransType = 2 // SALE
        request.TenderType = 1 // CREDIT
        request.Amount = amountInCents.toString()
        request.ECRRefNum = ecrRefNum
        
        posLink.PaymentRequest = request

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    val response = posLink.PaymentResponse
                    if (result != null && result.Code == ProcessTransResult.ProcessTransResultCode.OK) {
                        if (response != null && response.ResultCode == "000000") {
                            callback.onSuccess(response.AuthCode ?: "", response.RetrievalReferenceNumber ?: "")
                        } else {
                            callback.onFailure(response?.ResultTxt ?: "Declined")
                        }
                    } else {
                        callback.onFailure(result?.Msg ?: "SDK Error")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onFailure(e.message ?: "Unknown Error")
                }
            } finally {
                if (activePosLink === posLink) activePosLink = null
            }
        }
    }

    override fun voidTransaction(refNum: String, callback: IPaymentProvider.PaymentCallback) {
        Log.w(TAG, "Initiating PAX VOID: $refNum")
        val posLink = PosLink()
        posLink.SetCommSetting(getCommSetting())
        activePosLink = posLink

        val request = PaymentRequest()
        request.TransType = 4 // VOID
        request.OrigRefNum = refNum
        request.ECRRefNum = "V" + System.currentTimeMillis()
        
        posLink.PaymentRequest = request

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    val response = posLink.PaymentResponse
                    if (result != null && result.Code == ProcessTransResult.ProcessTransResultCode.OK && response?.ResultCode == "000000") {
                        callback.onSuccess("VOID_OK", response.RetrievalReferenceNumber ?: "")
                    } else {
                        callback.onFailure(response?.ResultTxt ?: "VOID Failed")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onFailure(e.message ?: "VOID Error") }
            } finally {
                if (activePosLink === posLink) activePosLink = null
            }
        }
    }

    override fun refundTransaction(refNum: String, amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        Log.w(TAG, "Initiating PAX REFUND: $refNum, Amount: $amountInCents")
        val posLink = PosLink()
        posLink.SetCommSetting(getCommSetting())
        activePosLink = posLink

        val request = PaymentRequest()
        request.TransType = 3 // RETURN (refund)
        request.OrigRefNum = refNum
        request.Amount = amountInCents.toString()
        request.ECRRefNum = "R" + System.currentTimeMillis()
        
        posLink.PaymentRequest = request

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    val response = posLink.PaymentResponse
                    if (result != null && result.Code == ProcessTransResult.ProcessTransResultCode.OK && response?.ResultCode == "000000") {
                        callback.onSuccess("REFUND_OK", response.RetrievalReferenceNumber ?: "")
                    } else {
                        callback.onFailure(response?.ResultTxt ?: "REFUND Failed")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onFailure(e.message ?: "REFUND Error") }
            } finally {
                if (activePosLink === posLink) activePosLink = null
            }
        }
    }

    /**
     * VIP-card tap detection (membership identification, not EMV payment --
     * that's startSale/etc. above, unaffected). Previously routed through
     * the NeptuneLite DAL (com.pax.dal.IPicc), which has no real backing AAR
     * in this repo (see core/hardware's com.pax stub package) -- so this always
     * silently fell back to the mock branch below regardless of IS_MOCK.
     * PAX support confirmed (ticket 3822-1) NeptuneLite isn't the supported
     * path for anything on IM30/IM25; checking the already-bundled
     * libs/pax/PAX_POSLinkAndroid_20260202.aar directly (javap) confirmed it
     * ships its own real com.pax.poslink.peripheries.PiccManager with a
     * CardInfo.getSerialInfo() UID read -- same shape as the old DAL call,
     * zero new dependencies. Only Mifare detection is available via this
     * API (DetectMode.ONLY_M, no broader ISO14443 A/B mode like the DAL
     * had) -- fine here since bank cards go through startSale's POSLink
     * PaymentRequest path, never this one.
     */
    override fun startCardDetection(amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        if (HardwareConfig.isMock) {
            Log.d(TAG, "Mock mode: simulating card tap in 3s")
            detectionJob = CoroutineScope(Dispatchers.Main).launch {
                delay(3000)
                callback.onCardDetected("MIFARE", "VIP_CARD_UID_6789")
                callback.onSuccess("", "", "MOCK_TAP")
            }
            return
        }

        val piccManager = PiccManager.getInstance(context)
        detectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                piccManager.open()
                while (isActive) {
                    // A per-poll PiccException (e.g. detect timeout with no card present) means
                    // "nothing yet, keep polling" -- only exceptions from open() above, or from
                    // outside this inner catch, should abort the whole detection loop below.
                    val info = try {
                        piccManager.detect(PiccManager.DetectMode.ONLY_M)
                    } catch (e: PiccException) {
                        null
                    }
                    if (info != null) {
                        val uid = info.serialInfo?.joinToString("") { "%02X".format(it) } ?: ""
                        withContext(Dispatchers.Main) {
                            callback.onCardDetected("MIFARE", uid)
                            callback.onSuccess("", uid, "MIFARE")
                        }
                        break
                    }
                    delay(300)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection error: ${e.message}")
                withContext(Dispatchers.Main) { callback.onFailure("Detection Error: ${e.message}") }
            } finally {
                stopCardDetection()
            }
        }
    }

    override fun stopCardDetection() {
        detectionJob?.cancel()
        detectionJob = null
        try {
            PiccManager.getInstance(context).close()
        } catch (e: Exception) {
            // No open session to close (e.g. mock mode never opened one), or already closed -- fine.
        }
    }

    override fun cancelCurrentTransaction() {
        Log.w(TAG, "User requested PAX transaction cancel")
        try {
            activePosLink?.CancelTrans()
        } catch (e: Exception) {
            Log.e(TAG, "CancelTrans failed: ${e.message}")
        }
    }

    override fun closeBatch(callback: IPaymentProvider.PaymentCallback) {
        Log.w(TAG, "Initiating PAX BATCH CLOSE (Settle)")
        val posLink = PosLink()
        posLink.SetCommSetting(getCommSetting())
        activePosLink = posLink

        val request = BatchRequest()
        request.TransType = 1 // BATCH CLOSE
        request.EDCType = 0 // ALL
        posLink.BatchRequest = request

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    val response = posLink.BatchResponse
                    if (result != null && result.Code == ProcessTransResult.ProcessTransResultCode.OK) {
                        if (response != null && response.ResultCode == "000000") {
                            callback.onSuccess("BATCH_OK", "")
                        } else {
                            callback.onFailure(response?.ResultTxt ?: "Batch Failed")
                        }
                    } else {
                        callback.onFailure(result?.Msg ?: "Batch SDK Error")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onFailure(e.message ?: "Batch Unknown Error")
                }
            } finally {
                if (activePosLink === posLink) activePosLink = null
            }
        }
    }
}
