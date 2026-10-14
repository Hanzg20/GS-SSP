package com.goldsky.ssp.payment.wizarpos

import android.util.Log
import com.cloudpos.OperationResult
import com.cloudpos.POSTerminal
import com.cloudpos.rfcardreader.RFCardReaderDevice
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.payment.hardware.CardBrands
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * WizarPOS Payment Provider implementation.
 * Integrates with PAYWizard app via Local Socket (127.0.0.1, port 6031 or 6666).
 * Aligned with WIZARPOSPaymentAppIntegrationProtocolV2.3.13 and P3 Protocol.
 */
class WizarPosPaymentProvider(private val terminal: POSTerminal?) : IPaymentProvider {

    companion object {
        private const val TAG = "WizarPosPayment"
        /** PAYWizard RespCode for "cancelled by user" (cancel key or payment-screen timeout). */
        const val RESP_CANCELLED = "-139"
        /** "system is busy, processing transaction": an earlier sale is still open in PAYWizard. */
        const val RESP_BUSY = "-11"
        private const val SILENT_SALE_QUERIES = 6
        private const val SILENT_SALE_QUERY_GAP_MS = 10_000L
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
        val EnableReceipt: Boolean = false,
        val isPrint: String = "false"
    )

    /** ISO 4217 numeric code for this terminal's region (CA -> CAD 124, else USD 840). */
    private fun currencyCode() = if (CoreConfig.region == "CA") "124" else "840"

    /**
     * Technician payment test runner (WizarPOS/Nuvei AIDL test cases): sends
     * [fields] as given -- string values, CallerName/CurrencyCode added unless
     * present -- after the P3 handshake, and returns PAYWizard's raw JSON
     * answer, or null when PAYWizard didn't answer. No record is written and
     * nothing is reversed: the runner owns the whole case.
     */
    suspend fun executeRaw(fields: Map<String, String>): String? = withContext(Dispatchers.IO) {
        val nonce = ByteArray(4).apply { java.util.Random().nextBytes(this) }
        WizarPosSocketClient.sendRequest(nonce, WizarPosP3Protocol.CTRL_HANDSHAKE_REQ) ?: return@withContext null
        val all = mapOf("CallerName" to "GS-SSP", "CurrencyCode" to currencyCode()) + fields
        val body = kotlinx.serialization.json.JsonObject(all.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
        Log.i(TAG, "Test runner request: $body")
        val bytes = WizarPosSocketClient.sendRequest(body.toString()) ?: return@withContext null
        String(bytes, Charsets.UTF_8)
    }

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

    /**
     * PAYWizard QueryTransaction by OriTransIndexCode. Measured on the Q3mini
     * (2026-09-25): an approved sale answers TransResult=true with the
     * original Purchase's fields (TransID, TraceNum, InvoiceNum, RRN, amount,
     * CardBrand...); a sale that never went through answers -125 "the
     * original transaction does not exist"; a reversed sale still answers as
     * the approved Purchase. The identifiers are cached so a reversal after
     * an app restart can still send OriTransId -- without it the terminal
     * asks for manual input (WizarPOS, 2026-09-24).
     */
    override fun queryTransaction(refNum: String, callback: (IPaymentProvider.QueryResult) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                val randomBytes = ByteArray(4).apply { java.util.Random().nextBytes(this) }
                WizarPosSocketClient.sendRequest(randomBytes, WizarPosP3Protocol.CTRL_HANDSHAKE_REQ)
                val request = GlobalRequest(
                    TransType = "QueryTransaction",
                    CurrencyCode = currencyCode(),
                    OriTransIndexCode = refNum,
                    TransIndexCode = "Q-" + java.lang.System.currentTimeMillis()
                )
                val bytes = WizarPosSocketClient.sendRequest(json.encodeToString(request))
                if (bytes == null) {
                    IPaymentProvider.QueryResult.Error("no response from PAYWizard")
                } else {
                    val root = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
                    fun field(k: String) = root[k]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                    val code = field("RespCode")
                    when {
                        field("TransResult")?.toBoolean() == true && field("TransType") == "Purchase" -> {
                            val amount = field("TransAmount")?.toIntOrNull()
                            saleIds[refNum] = SaleIds(field("TraceNum"), field("InvoiceNum"), field("TransID"), field("RRN"))
                            amount?.let { saleAmounts[refNum] = it }
                            IPaymentProvider.QueryResult.Approved(amount)
                        }
                        code == "-125" -> IPaymentProvider.QueryResult.NotFound
                        else -> IPaymentProvider.QueryResult.Error("$code ${field("RespDesc") ?: ""}".trim())
                    }
                }
            } catch (e: Exception) {
                IPaymentProvider.QueryResult.Error("query failed: ${e.message}")
            }
            Log.i(TAG, "QueryTransaction $refNum -> $result")
            callback(result)
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
            val localPayWizard = WizarPosSocketClient.targetHost.ifBlank { "127.0.0.1" } == "127.0.0.1"

            if (handshakeRes == null && localPayWizard) {
                // No answer from this terminal's own PAYWizard: busy (or not running).
                // Nothing sent yet, nothing charged -- fail now rather than wait 200 s on the sale.
                Log.w(TAG, "P3 Handshake got no reply; not sending the sale")
                callback.onFailure("Payment service busy (P3 handshake no reply)", true)
                return
            } else if (handshakeRes == null) {
                // A networked UPT (Q3mini over Wi-Fi): its firmware doesn't answer the handshake.
                Log.w(TAG, "P3 Handshake got no reply from the remote terminal; sending the sale directly")
            } else if (!handshakeRes.contentEquals(randomBytes)) {
                Log.w(TAG, "P3 Handshake mismatch, attempting to proceed anyway...")
            } else {
                Log.i(TAG, "P3 Handshake Successful")
            }

            // Step 4: Transaction Request (CTRL=0x01)
            val requestJson = json.encodeToString(request)
            val responseBytes = WizarPosSocketClient.sendRequest(requestJson)
            
            if (responseBytes != null) {
                val response = String(responseBytes, Charsets.UTF_8)
                val root = json.parseToJsonElement(response).jsonObject
                // Batch totals (BatchDetailInfo) for reconciliation. Settle
                // responses carry no full card data, only masked lists.
                if (request.TransType == "Settle") callback.onRawResponse(response)
                
                // TransResult is the primary success indicator (boolean)
                val isSuccess = root["TransResult"]?.jsonPrimitive?.content?.toBoolean() ?: false
                val resultCode = root["RespCode"]?.jsonPrimitive?.content ?: "999"
                val resultMsg = root["RespDesc"]?.jsonPrimitive?.content ?: "Unknown Error"
                
                // Empty/"null" strings mean the terminal didn't fill the field.
                fun field(k: String) = root[k]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                val entryMode = CardBrands.wizarPosEntryMode(field("EntryMode"))
                val info = IPaymentProvider.CardInfo(
                    scheme = field("TransScheme"),
                    brand = CardBrands.brand(field("CardBrand"), field("EmvAid")),
                    aid = field("EmvAid"),
                    bin = field("CardNum")?.filter { it.isDigit() }?.take(6)?.takeIf { it.length == 6 },
                    entryMode = entryMode,
                )
                // A card was read (declined too): report it, so a declined row
                // shows the real card instead of the provisional CREDIT_CARD.
                val cardRead = info.brand != null || info.aid != null || info.bin != null
                if (cardRead) Log.i(TAG, "Card info: scheme=${info.scheme} brand=${info.brand} aid=${info.aid} bin=${info.bin} entry=$entryMode")

                if (isSuccess) {
                    val authNo = root["AuthCode"]?.jsonPrimitive?.content ?: "OK"
                    val rrn = root["RRN"]?.jsonPrimitive?.content
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
                    callback.onSuccess(authNo, originalRef, entryMode)
                } else {
                    // The reason only reached the screen before: a decline left nothing to look
                    // at afterwards (2026-10-14, Ourea declines answered in 0.5 s with no card read).
                    Log.w(TAG, "${request.TransType} not approved: RespCode=$resultCode RespDesc=$resultMsg cardRead=$cardRead")
                    if (cardRead) callback.onCardInfo(info)
                    // -139 "cancelled by user": cancel key or payment-screen timeout.
                    if (resultCode == RESP_CANCELLED) callback.onCancelled()
                    if (resultCode == RESP_BUSY) {
                        // PAYWizard still holds an earlier sale it never finished (seen 2026-10-14:
                        // its payment app hung after an approval and refused every sale for
                        // ~10 min). Nothing was sent to the bank for this one.
                        callback.onFailure("Card terminal is still busy with an earlier payment. " +
                            "Wait for it to finish or restart PAYWizard, then try again.", isHardwareFault = true)
                    } else if (!cardRead && resultMsg.contains("payment package", ignoreCase = true)) {
                        // OPC has no payment app set (seen right after leaving debug mode,
                        // 2026-10-13): no card screen, nothing charged; retrying won't help.
                        callback.onFailure("Card terminal not set up: OPC has no payment app selected ($resultMsg)", isHardwareFault = true)
                    } else {
                        callback.onFailure("Payment Error: $resultMsg ($resultCode)")
                    }
                }
            } else if (request.TransType == "Purchase") {
                resolveSilentSale(originalRef, callback)
            } else {
                callback.onFailure("Communication Timeout (P3)", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execute error: ${e.message}")
            callback.onFailure("Internal Error: ${e.message}", true)
        }
    }

    /**
     * The sale was sent but PAYWizard never answered. That is NOT a decline: on
     * 2026-10-14 the card was read and the bank approved, then PAYWizard's payment
     * app hung and our read timed out -- the sale was recorded DECLINED while the
     * customer had been charged. Ask PAYWizard what became of it for a minute;
     * only a "no such sale" is a failure, and if it still can't say, the outcome
     * stays unknown (PENDING) instead of being guessed.
     */
    private suspend fun resolveSilentSale(ref: String, callback: IPaymentProvider.PaymentCallback) {
        Log.w(TAG, "No answer to sale $ref; asking PAYWizard for its result")
        callback.onProgress("Checking the card terminal for the result...")
        repeat(SILENT_SALE_QUERIES) { attempt ->
            val r = kotlinx.coroutines.CompletableDeferred<IPaymentProvider.QueryResult>()
            queryTransaction(ref) { r.complete(it) }
            when (val result = r.await()) {
                is IPaymentProvider.QueryResult.Approved -> {
                    Log.i(TAG, "Silent sale $ref was approved (query attempt ${attempt + 1})")
                    callback.onSuccess("OK", ref, "UNKNOWN")
                    return
                }
                IPaymentProvider.QueryResult.NotFound -> {
                    callback.onFailure("Payment not completed: the card terminal has no record of it", isHardwareFault = true)
                    return
                }
                is IPaymentProvider.QueryResult.Error -> Log.w(TAG, "Silent sale $ref still unknown: ${result.message}")
            }
            if (attempt < SILENT_SALE_QUERIES - 1) kotlinx.coroutines.delay(SILENT_SALE_QUERY_GAP_MS)
        }
        callback.onResultUnknown("The card terminal didn't give a result -- the card MAY have been charged. " +
            "Don't charge again: check the card terminal's screen or its transaction list first.")
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

    /**
     * Ourea Eye commands, sent one at a time in the order issued. Each used to
     * get its own coroutine and socket, so quick taps on "+" could land out of
     * order and the display stuck on an older quantity.
     */
    private val eyeQueue = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { q ->
        CoroutineScope(Dispatchers.IO).launch { for (send in q) runCatching { send() } }
    }

    private fun toEye(send: suspend () -> Unit) { eyeQueue.trySend(send) }

    // The Eye takes about a second per command: with quick scans only the newest
    // cart still waiting is sent, older ones are skipped (idle/thank-you never are).
    private val cartVersion = java.util.concurrent.atomic.AtomicLong()

    override fun showIdleScreen() {
        toEye { WizarPosSocketClient.sendDisplayIdle() }
    }

    override fun updateLiveCart(items: List<IPaymentProvider.CfdItem>, subtotalCents: Int, taxCents: Int) {
        val version = cartVersion.incrementAndGet()
        toEye {
            if (version != cartVersion.get()) return@toEye // a newer cart is queued
            val totalCents = subtotalCents + taxCents
            val itemsJsonList = items.map { item ->
                kotlinx.serialization.json.JsonObject(
                    mapOf(
                        "name" to kotlinx.serialization.json.JsonPrimitive(item.name),
                        "qty" to kotlinx.serialization.json.JsonPrimitive(item.qty),
                        "priceCents" to kotlinx.serialization.json.JsonPrimitive(item.priceCents)
                    )
                )
            }
            val payloadObj = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "TransType" to kotlinx.serialization.json.JsonPrimitive("DisplayCart"),
                    "CallerName" to kotlinx.serialization.json.JsonPrimitive("GS-SSP"),
                    "SubtotalCents" to kotlinx.serialization.json.JsonPrimitive(subtotalCents),
                    "TaxCents" to kotlinx.serialization.json.JsonPrimitive(taxCents),
                    "TotalCents" to kotlinx.serialization.json.JsonPrimitive(totalCents),
                    "Items" to kotlinx.serialization.json.JsonArray(itemsJsonList)
                )
            )
            WizarPosSocketClient.sendDisplayCart(payloadObj.toString())
        }
    }

    override fun showThankYouScreen(orderNumber: Int?) {
        toEye {
            WizarPosSocketClient.sendDisplayThankYou(orderNumber)
        }
    }

    override fun showEyeRotation(degrees: Int) {
        toEye {
            val payload = """{"TransType":"DisplayRotate","Rotation":$degrees,"CallerName":"GS-SSP"}"""
            WizarPosSocketClient.sendEyeRequest(payload)
        }
    }
}
