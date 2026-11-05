package com.goldsky.ssp.flex.logic

import android.util.Log
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The money side of a sale: charged at once, or a hold settled once the controller confirms.
 * [Real] goes to the terminal's payment app; [Simulated] is the emulator's stand-in.
 */
interface FlexPayment {
    sealed class Result {
        data class Approved(val authCode: String, val entryMode: String, val card: IPaymentProvider.CardInfo?) : Result()
        object Declined : Result()
        /** Card terminal trouble, or no answer: treat as not paid, release anything that may be held. */
        data class Failed(val message: String) : Result()
    }

    fun hold(cents: Int, ref: String, done: (Result) -> Unit)
    fun sale(cents: Int, ref: String, done: (Result) -> Unit)
    /** Settle the hold [ref] at [cents]. */
    fun complete(ref: String, cents: Int, done: (Boolean) -> Unit)
    /** Release the hold [ref] (nothing charged). */
    fun release(ref: String, done: (Boolean) -> Unit)
    /** Give back the sale [ref]: void, else refund. */
    fun reverse(ref: String, cents: Int, done: (Boolean) -> Unit)

    /** Like [reverse], saying how: VOID, REFUND or NONE (not given back). */
    fun undo(ref: String, cents: Int, done: (Boolean, String) -> Unit)

    /** The completion that settled the hold [ref] (what a void of a settled hold reverses), if known. */
    fun settledRef(ref: String): String? = null

    /** See IPaymentProvider.rememberSale. */
    fun rememberSale(ref: String, cents: Int, trace: String?, invoice: String?, transId: String?, rrn: String?) {}

    /**
     * The payment app's approved answers for [ref] so far, by TransType (sanitised; e.g. PreAuth +
     * AuthCompletion) -- sent with the order's row (transactions.acquirer_response).
     */
    fun answers(ref: String): kotlinx.serialization.json.JsonObject? = null

    class Real(private val provider: IPaymentProvider) : FlexPayment {
        private val raws = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, kotlinx.serialization.json.JsonElement>>()

        private fun keep(ref: String, type: String, json: String) {
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull()?.let {
                raws.getOrPut(ref) { java.util.concurrent.ConcurrentHashMap() }[type] = it
            }
        }

        override fun answers(ref: String) = raws[ref]?.let { kotlinx.serialization.json.JsonObject(it.toMap()) }

        private fun callback(ref: String, type: String, done: (Result) -> Unit) = object : IPaymentProvider.PaymentCallback {
            override fun onRawResponse(json: String) = keep(ref, type, json)
            private var card: IPaymentProvider.CardInfo? = null
            private var finished = false
            private fun finish(r: Result) { if (!finished) { finished = true; done(r) } }
            override fun onCardInfo(info: IPaymentProvider.CardInfo) { card = info }
            override fun onCancelled() = finish(Result.Declined)
            override fun onSuccess(authCode: String, refNum: String, entryMode: String) =
                finish(Result.Approved(authCode, entryMode, card))
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                Log.w("FlexPayment", "Not approved: $errorMsg (hardware fault: $isHardwareFault)")
                finish(if (isHardwareFault) Result.Failed(errorMsg) else Result.Declined)
            }
            override fun onProgress(message: String) {}
        }

        private fun yesNo(ref: String?, type: String, done: (Boolean) -> Unit) = object : IPaymentProvider.PaymentCallback {
            override fun onRawResponse(json: String) { if (ref != null) keep(ref, type, json) }
            override fun onSuccess(authCode: String, refNum: String, entryMode: String) = done(true)
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                Log.e("FlexPayment", "Follow-up failed: $errorMsg")
                done(false)
            }
            override fun onProgress(message: String) {}
        }

        override fun hold(cents: Int, ref: String, done: (Result) -> Unit) = provider.startPreAuth(cents, ref, callback(ref, "PreAuth", done))
        override fun sale(cents: Int, ref: String, done: (Result) -> Unit) = provider.startSale(cents, ref, callback(ref, "Purchase", done))
        override fun complete(ref: String, cents: Int, done: (Boolean) -> Unit) = provider.completeAuth(ref, cents, yesNo(ref, "AuthCompletion", done))
        override fun release(ref: String, done: (Boolean) -> Unit) = provider.voidAuth(ref, yesNo(null, "AuthVoid", done))
        override fun reverse(ref: String, cents: Int, done: (Boolean) -> Unit) =
            provider.voidOrRefund(ref, cents) { ok, _ -> done(ok) }
        override fun undo(ref: String, cents: Int, done: (Boolean, String) -> Unit) =
            provider.voidOrRefund(ref, cents) { ok, how -> done(ok, how) }
        override fun settledRef(ref: String) = provider.settledRef(ref)
        override fun rememberSale(ref: String, cents: Int, trace: String?, invoice: String?, transId: String?, rrn: String?) =
            provider.rememberSale(ref, cents, trace, invoice, transId, rrn)
    }

    /** Emulator only (see FlexViewModel.simulated): approves after a moment unless told to decline. */
    class Simulated : FlexPayment {
        var declineNext = false
        private fun later(block: () -> Unit) {
            CoroutineScope(Dispatchers.Main).launch { delay(1800); block() }
        }
        private fun answer(done: (Result) -> Unit) = later {
            done(if (declineNext.also { declineNext = false }) Result.Declined else Result.Approved("SIM", "CONTACTLESS", null))
        }
        override fun hold(cents: Int, ref: String, done: (Result) -> Unit) = answer(done)
        override fun sale(cents: Int, ref: String, done: (Result) -> Unit) = answer(done)
        override fun complete(ref: String, cents: Int, done: (Boolean) -> Unit) = done(true)
        override fun release(ref: String, done: (Boolean) -> Unit) = done(true)
        override fun reverse(ref: String, cents: Int, done: (Boolean) -> Unit) = done(true)
        override fun undo(ref: String, cents: Int, done: (Boolean, String) -> Unit) = done(true, "VOID")
    }
}
