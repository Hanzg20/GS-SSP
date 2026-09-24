package com.goldsky.ssp.payment.wizarpos

import android.util.Log
import com.cloudpos.OperationResult
import com.cloudpos.POSTerminal
import com.cloudpos.rfcardreader.RFCardReaderDevice
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.common.CoreConfig
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
    private var detectionJob: Job? = null
    private var rfCardDevice: RFCardReaderDevice? = null

    // TransIndexCode -> amount of sales this process started, so a later
    // Reversal can carry TransAmount like the vendor's own demo does
    // (Serial&SocketDemo240910 MainActivity, B_TRAN_REVERSAL).
    private val saleAmounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // TransIndexCode -> identifiers PAYWizard returned for an approved sale.
    // Measured 2026-09-24 on a Q3mini (test host): a Reversal carrying only
    // OriTransIndexCode + TransAmount was rejected "-191 Original transaction
    // not found", while a Refund with the same OriTransIndexCode succeeded --
    // the protocol only documents OriTransIndexCode lookup for Refund. So the
    // Reversal now also carries the sale's InvoiceNum/TraceNum/TransID/RRN.
    private data class SaleIds(val traceNum: String?, val invoiceNum: String?, val transId: String?, val rrn: String?)
    private val saleIds = java.util.concurrent.ConcurrentHashMap<String, SaleIds>()

    /**
     * Data model aligned with WizarPOS GlobalRequest.java
     */
    @Serializable
    private data class GlobalRequest(
        val TransType: String,
        val TransIndexCode: String? = null,
        val TransAmount: String? = null,
        val CallerName: String = "GS-SSP",
        // No default on purpose: kotlinx.serialization drops fields equal to
        // their default, so the old `= "124"` meant a CAD sale never sent a
        // CurrencyCode at all and PAYWizard fell back to USD (840) -- seen on
        // the Q3mini emulator 2026-09-24. WizarPOS confirmed 124 must be set
        // in the message as well as in the OPC/Nuvei parameters, and the
        // protocol marks it mandatory for every request type.
        val CurrencyCode: String,
        val OriTransIndexCode: String? = null,
        // Other ways PAYWizard can identify the original sale. Null = omitted.
        val OriTraceNum: String? = null,
        val OriInvoiceNum: String? = null,
        val OriTransId: String? = null,
        val OriRrn: String? = null,
        val EnableReceipt: Boolean = true,
        val isPrint: String = "true"
    )

    /** ISO 4217 numeric code for this terminal's region (CA -> CAD 124, else USD 840). */
    private fun currencyCode() = if (CoreConfig.region == "CA") "124" else "840"

    override fun startSale(amountInCents: Int, ecrRefNum: String, callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "Starting PAYWizard SALE: $amountInCents cents")
        saleAmounts[ecrRefNum] = amountInCents
        
        CoroutineScope(Dispatchers.Main).launch {
            callback.onProgress("CONNECTING TO TERMINAL...")
            

            val request = GlobalRequest(
                TransType = "Purchase",
                TransAmount = amountInCents.toString(),
                TransIndexCode = ecrRefNum,
                CurrencyCode = currencyCode()
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
                CurrencyCode = currencyCode(),
                TransAmount = saleAmounts[refNum]?.toString(),
                OriTransIndexCode = refNum,
                OriTraceNum = saleIds[refNum]?.traceNum,
                OriInvoiceNum = saleIds[refNum]?.invoiceNum,
                OriTransId = saleIds[refNum]?.transId,
                OriRrn = saleIds[refNum]?.rrn,
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
                CurrencyCode = currencyCode(),
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
            // Step 2 & 3: Handshake Request (CTRL=0xF1) with 4 random bytes
            val randomBytes = ByteArray(4).apply { java.util.Random().nextBytes(this) }
            val handshakeRes = WizarPosSocketClient.sendRequest(randomBytes, WizarPosP3Protocol.CTRL_HANDSHAKE_REQ)
            
            if (handshakeRes == null || !handshakeRes.contentEquals(randomBytes)) {
                Log.w(TAG, "P3 Handshake failed or mismatch, attempting to proceed anyway...")
            } else {
                Log.i(TAG, "P3 Handshake Successful")
            }

            // Step 4: Transaction Request (CTRL=0x01)
            val requestJson = json.encodeToString(request)
            val responseBytes = WizarPosSocketClient.sendRequest(requestJson)
            
            if (responseBytes != null) {
                val response = String(responseBytes, Charsets.UTF_8)
                val root = json.parseToJsonElement(response).jsonObject
                
                // TransResult is the primary success indicator (boolean)
                val isSuccess = root["TransResult"]?.jsonPrimitive?.content?.toBoolean() ?: false
                val resultCode = root["RespCode"]?.jsonPrimitive?.content ?: "999"
                val resultMsg = root["RespDesc"]?.jsonPrimitive?.content ?: "Unknown Error"
                
                if (isSuccess) {
                    val authNo = root["AuthCode"]?.jsonPrimitive?.content ?: "OK"
                    val rrn = root["RRN"]?.jsonPrimitive?.content
                    // Empty/"null" strings mean the terminal didn't fill the field.
                    fun field(k: String) = root[k]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                    val info = IPaymentProvider.CardInfo(
                        scheme = field("TransScheme"),
                        brand = field("CardBrand"),
                        aid = field("EmvAid"),
                        bin = field("CardNum")?.filter { it.isDigit() }?.take(6)?.takeIf { it.length == 6 }
                    )
                    Log.i(TAG, "Card info: scheme=${info.scheme} brand=${info.brand} aid=${info.aid} bin=${info.bin}")
                    Log.i(TAG, "Approved: TransIndexCode=$originalRef RRN=$rrn")
                    if (request.TransType == "Purchase") {
                        saleIds[originalRef] = SaleIds(field("TraceNum"), field("InvoiceNum"), field("TransID"), rrn)
                    }
                    callback.onCardInfo(info)
                    // refNum is what the caller later hands back to voidOrRefund, so
                    // it must be the key PAYWizard looks the original up by:
                    // OriTransIndexCode = "Original request TransIndexCode"
                    // (WIZARPOSPaymentAppIntegrationProtocolV2.3.13, request fields;
                    // the vendor demo reverses "ID123456" = the sale's TransIndexCode).
                    // This used to return the bank RRN, so every automatic
                    // VOID/REFUND after a dispense failure referenced a
                    // transaction PAYWizard couldn't find.
                    // entryMode CTLS as a placeholder, real one could be parsed from CardNum/TransType
                    callback.onSuccess(authNo, originalRef, "PAYWIZARD")
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

    /**
     * VIP-card tap detection (membership identification, not EMV payment).
     *
     * IMPORTANT for WizarPOS: If this is called before startSale (like in
     * feature:wash), we must NOT keep the RFCardReaderDevice open while
     * calling startSale via Socket, or PAYWizard will fail to open it
     * (sharing violation). For Sale flow, we return immediate success to
     * let PAYWizard handle the UI and reader.
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

        // For WizarPOS SALE flow, PAYWizard handles the "Tap Card" UI and
        // hardware control itself. We skip our own detection to avoid
        // interference.
        if (amountInCents > 0) {
            Log.i(TAG, "SALE flow: Skipping local card detection, letting PAYWizard handle it")
            callback.onSuccess("", "", "PENDING_SALE")
            return
        }

        // Only do local MIFARE detection if amount is 0 (e.g. membership check only)
        val t = terminal
        if (t == null) {
            callback.onFailure("WizarPOS terminal not initialized", isHardwareFault = true)
            return
        }

        detectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val device = rfCardDevice ?: (t.getDevice(POSTerminal.DEVICE_NAME_RF_CARD_READER) as? RFCardReaderDevice)
                    ?.also { rfCardDevice = it }
                if (device == null) {
                    withContext(Dispatchers.Main) { callback.onFailure("RF card reader unavailable", isHardwareFault = true) }
                    return@launch
                }

                device.open()
                val result = device.waitForCardPresent(30_000)
                if (result.resultCode == OperationResult.SUCCESS) {
                    val uid = result.card?.getID()?.joinToString("") { "%02X".format(it) } ?: ""
                    withContext(Dispatchers.Main) {
                        callback.onCardDetected("MIFARE", uid)
                        callback.onSuccess("", uid, "MIFARE")
                    }
                } else {
                    withContext(Dispatchers.Main) { callback.onFailure("Card detection failed (code ${result.resultCode})") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection error: ${e.message}")
                withContext(Dispatchers.Main) { callback.onFailure("Detection Error: ${e.message}") }
            } finally {
                try {
                    rfCardDevice?.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    override fun stopCardDetection() {
        try {
            rfCardDevice?.cancelRequest()
        } catch (e: Exception) {
            // No pending request to cancel -- fine.
        }
        detectionJob?.cancel()
        detectionJob = null
    }

    override fun cancelCurrentTransaction() {
        Log.w(TAG, "Cancellation not supported via synchronous P3 socket integration")
    }

    override fun closeBatch(callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "Requesting PAYWizard SETTLE")
        
        CoroutineScope(Dispatchers.Main).launch {
            val request = GlobalRequest(
                TransType = "Settle",
                CurrencyCode = currencyCode(),
                TransIndexCode = "S-" + java.lang.System.currentTimeMillis()
            )
            
            executeRequest(request, "SETTLE", callback)
        }
    }
}
