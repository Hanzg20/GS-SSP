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
        return CommSetting().apply {
            setCommType(activeConfig.commType)
            setDestIP(activeConfig.destIP)
            setDestPort(activeConfig.destPort)
            setTimeOut(activeConfig.timeout)
        }
    }

    override fun startSale(amountInCents: Int, ecrRefNum: String, callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "Initiating PAX SALE: $amountInCents cents, Ref: $ecrRefNum")

        val posLink = PosLink()
        posLink.commSetting = getCommSetting()
        activePosLink = posLink

        val request = PaymentRequest().apply {
            setTransType(2) // SALE
            setTenderType(1) // CREDIT
            setAmount(amountInCents.toString())
            setECRRefNum(ecrRefNum)
        }
        posLink.paymentRequest = request

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    val response = posLink.paymentResponse
                    if (result != null && result.code == ProcessTransResult.ProcessTransExitCode.OK) {
                        if (response != null && response.getResultCode() == "000000") {
                            callback.onSuccess(response.getAuthCode() ?: "", response.getRefNum() ?: "")
                        } else {
                            callback.onFailure(response?.getResultMsg() ?: "Declined")
                        }
                    } else {
                        callback.onFailure(result?.msg ?: "SDK Error")
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
        posLink.commSetting = getCommSetting()
        activePosLink = posLink

        val request = PaymentRequest().apply {
            setTransType(4) // VOID
            setOrigRefNum(refNum)
            setECRRefNum("V" + System.currentTimeMillis())
        }
        posLink.paymentRequest = request

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    if (result != null && result.code == ProcessTransResult.ProcessTransExitCode.OK && posLink.paymentResponse?.getResultCode() == "000000") {
                        callback.onSuccess("VOID_OK", posLink.paymentResponse.getRefNum() ?: "")
                    } else {
                        callback.onFailure(posLink.paymentResponse?.getResultMsg() ?: "VOID Failed")
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
        posLink.commSetting = getCommSetting()
        activePosLink = posLink

        val request = PaymentRequest().apply {
            // POSLink PaymentTransType has no REFUND value; RETURN=3 is what
            // returns funds to the card ("Returns payment amount to the card
            // open to buy" per the API Guide's PaymentTransType appendix).
            // 5 is POSTAUTH ("Completes an Authorization Only transaction"),
            // a different operation entirely -- do not restore that value.
            setTransType(3) // RETURN (refund)
            setOrigRefNum(refNum)
            setAmount(amountInCents.toString())
            setECRRefNum("R" + System.currentTimeMillis())
        }
        posLink.paymentRequest = request

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    if (result != null && result.code == ProcessTransResult.ProcessTransExitCode.OK && posLink.paymentResponse?.getResultCode() == "000000") {
                        callback.onSuccess("REFUND_OK", posLink.paymentResponse.getRefNum() ?: "")
                    } else {
                        callback.onFailure(posLink.paymentResponse?.getResultMsg() ?: "REFUND Failed")
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
}
