package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Batch settlement (Settle) for the daily worker and the technician's
 * "settle now" button. Every attempt is recorded in maintenance_records
 * (action BATCH_CLOSE) with the batch totals PAYWizard returns, so a day's
 * transactions can be reconciled against what was actually settled --
 * before this, a success only wrote "AUTO_BATCH_CLOSE" with no numbers.
 */
object SettlementManager {
    private const val TAG = "SettlementManager"

    // Settle can take a while on a real host; PAYWizard's own limit is ~180s.
    private const val TIMEOUT_MS = 200_000L

    data class Outcome(val ok: Boolean, val message: String, val totals: Totals?)

    /** BatchDetailInfo fields (protocol V2.3.x); amounts as PAYWizard sends them. */
    data class Totals(
        val batchId: String?,
        val creditCount: String?,
        val creditAmount: String?,
        val debitCount: String?,
        val debitAmount: String?,
        val settleStatus: String?,
    ) {
        fun summary() = "batch $batchId: credit $creditCount / $creditAmount, debit $debitCount / $debitAmount (status $settleStatus)"
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** [trigger] = "AUTO" (daily worker) or "MANUAL" (technician). One settle at a time. */
    suspend fun settle(context: Context, trigger: String): Outcome = mutex.withLock {
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN"
        val provider = PaymentProviderFactory.getPaymentProvider(context, DeviceRepository.getPersistedHardwareVendor())
        val done = CompletableDeferred<Outcome>()
        var raw: String? = null
        Log.i(TAG, "Settle requested ($trigger)")
        provider.closeBatch(object : IPaymentProvider.PaymentCallback {
            override fun onRawResponse(json: String) {
                raw = json
            }

            override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                done.complete(Outcome(true, "Settled", parseTotals(raw)))
            }

            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                done.complete(Outcome(false, errorMsg, parseTotals(raw)))
            }

            override fun onProgress(message: String) {
                Log.d(TAG, message)
            }
        })
        val outcome = withTimeoutOrNull(TIMEOUT_MS) { done.await() }
            ?: Outcome(false, "No response within ${TIMEOUT_MS / 1000}s", null)
        Log.i(TAG, "Settle ($trigger) ok=${outcome.ok}: ${outcome.message} ${outcome.totals?.summary() ?: ""}")
        DiagnosticManager.recordMaintenance(sn, "BATCH_CLOSE", details(trigger, outcome))
        if (!outcome.ok) {
            DiagnosticManager.reportError(sn, "BATCH_CLOSE_FAILED", severity = "ERROR", trace = "$trigger: ${outcome.message}")
        }
        outcome
    }

    internal fun parseTotals(raw: String?): Totals? = runCatching {
        val info = json.parseToJsonElement(raw ?: return null).jsonObject["BatchDetailInfo"] ?: return null
        // Tolerate it arriving as a JSON-encoded string.
        val obj = runCatching { info.jsonObject }.getOrElse { json.parseToJsonElement(info.jsonPrimitive.content).jsonObject }
        // The protocol doc puts the totals directly in BatchDetailInfo, but
        // PAYWizard (Q3mini, 2026-09-25) nests them in a "Summary" object and
        // sends counts/amounts as numbers -- read Summary first.
        val summary = runCatching { obj["Summary"]?.jsonObject }.getOrNull() ?: obj
        fun f(k: String) = summary[k]?.jsonPrimitive?.contentOrNull
        Totals(f("BatchId"), f("CreditTotalCount"), f("CreditTotalAmount"), f("DebitTotalCount"), f("DebitTotalAmount"), f("SettleStatus"))
    }.getOrNull()

    private fun details(trigger: String, o: Outcome): JsonObject = buildJsonObject {
        put("trigger", trigger)
        put("ok", o.ok)
        put("message", o.message)
        o.totals?.let {
            put("batch_id", it.batchId)
            put("credit_count", it.creditCount)
            put("credit_amount", it.creditAmount)
            put("debit_count", it.debitCount)
            put("debit_amount", it.debitAmount)
            put("settle_status", it.settleStatus)
        }
    }
}
