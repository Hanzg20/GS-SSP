package com.goldsky.carwash.payment.hardware.pax

import android.content.Context
import android.util.Log
import com.goldsky.carwash.payment.hardware.IPaymentProvider
import com.pax.dal.IDAL
import com.pax.dal.IPicc
import com.pax.dal.entity.EDetectMode
import com.pax.poslink.*
import kotlinx.coroutines.*
import kotlinx.coroutines.launch

/**
 * PAX implementation of IPaymentProvider.
 * Wraps POSLink for SALE, VOID, and RETURN (refund) operations.
 * Supports dynamic switching between AIDL (Real Hardware) and TCP (Windows Simulator).
 */
class PaxPaymentProvider(
    private val context: Context,
    private val dalProvider: () -> IDAL?
) : IPaymentProvider {
    private val TAG = "PaxPayment"
    private var detectionJob: Job? = null

    /**
     * Configuration for PAX Communication.
     * In a production environment, this would come from ConfigManager/Cloud.
     */
    data class PaxConfig(
        val commType: String = if (android.os.Build.PRODUCT.contains("sdk") || android.os.Build.MODEL.contains("Emulator")) "TCP" else "AIDL",
        val destIP: String = if (android.os.Build.PRODUCT.contains("sdk") || android.os.Build.MODEL.contains("Emulator")) "10.0.2.2" else "127.0.0.1",
        val destPort: String = "10009",
        val timeout: String = "60000"
    )

    private var activeConfig = PaxConfig()

    /**
     * The [PosLink] instance for whichever SALE/VOID/REFUND call is currently
     * in flight, so [cancelCurrentTransaction] has something to call
     * `CancelTrans()` on. Each transaction method creates its own short-lived
     * [PosLink] (that's how the SDK is meant to be used -- see the request
     * objects being rebuilt per call), so this is only a handle to the
     * *current* one, not a long-lived shared instance.
     */
    @Volatile
    private var activePosLink: PosLink? = null

    fun updateConfig(config: PaxConfig) {
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
        posLink.
        SetCommSetting(getCommSetting())
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
        // POSLink PaymentTransType has no REFUND value; RETURN=3 is what
        // returns funds to the card ("Returns payment amount to the card
        // open to buy" per the API Guide's PaymentTransType appendix).
        // 5 is POSTAUTH ("Completes an Authorization Only transaction"),
        // a different operation entirely -- do not restore that value.
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
     * Start background polling for NFC cards (NFC/Mifare).
     * Migrated from legacy PaxScannerManager for HAL convergence.
     */
    override fun startCardDetection(amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        val dal = dalProvider()
        if (dal == null) {
            // Mock Fallback for Emulator/Dev
            Log.d(TAG, "PAX hardware unavailable: simulating card tap in 3s")
            detectionJob = CoroutineScope(Dispatchers.Main).launch {
                delay(3000)
                callback.onCardDetected("MIFARE", "VIP_CARD_UID_6789")
                // After detection, traditionally in this app we auto-proceed to sale
                // or just trigger the callback. Interface-wise, detection usually
                // implies "something is there".
                callback.onSuccess("", "", "MOCK_TAP")
            }
            return
        }

        detectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val picc = dal.picc
                picc.open()
                while (isActive) {
                    val info = picc.detect(EDetectMode.ALL)
                    if (info != null) {
                        val uid = info.serialInfo?.joinToString("") { "%02X".format(it) } ?: ""
                        val category = when (info.cardType.toInt().toChar()) {
                            'M' -> "MIFARE"
                            'A', 'B' -> "ISO_14443"
                            else -> "UNKNOWN"
                        }
                        withContext(Dispatchers.Main) {
                            callback.onCardDetected(category, uid)
                            callback.onSuccess("", uid, category)
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
    }

    override fun cancelCurrentTransaction() {
        Log.w(TAG, "User requested PAX transaction cancel")
        try {
            // PosLink#CancelTrans(): "used to cancel transaction while
            // POSLink is processing transaction... only effective before the
            // transaction is [sent to the host]" (API Guide §on PosLink
            // class). No-ops safely if nothing is in flight.
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
