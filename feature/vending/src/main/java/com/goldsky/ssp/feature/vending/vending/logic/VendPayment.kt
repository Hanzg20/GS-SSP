package com.goldsky.ssp.vending.logic

import android.util.Log
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The money side of a vend: a sale (item first) or a hold settled later (card first).
 * [Real] goes to the terminal's payment app; [Simulated] is the emulator's stand-in.
 */
interface VendPayment {
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

    class Real(private val provider: IPaymentProvider) : VendPayment {
        private fun callback(done: (Result) -> Unit) = object : IPaymentProvider.PaymentCallback {
            private var card: IPaymentProvider.CardInfo? = null
            private var finished = false
            private fun finish(r: Result) { if (!finished) { finished = true; done(r) } }
            override fun onCardInfo(info: IPaymentProvider.CardInfo) { card = info }
            override fun onCancelled() = finish(Result.Declined)
            override fun onSuccess(authCode: String, refNum: String, entryMode: String) =
                finish(Result.Approved(authCode, entryMode, card))
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                Log.w("VendPayment", "Not approved: $errorMsg (hardware fault: $isHardwareFault)")
                finish(if (isHardwareFault) Result.Failed(errorMsg) else Result.Declined)
            }
            override fun onProgress(message: String) {}
        }

        private fun yesNo(done: (Boolean) -> Unit) = object : IPaymentProvider.PaymentCallback {
            override fun onSuccess(authCode: String, refNum: String, entryMode: String) = done(true)
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                Log.e("VendPayment", "Follow-up failed: $errorMsg")
                done(false)
            }
            override fun onProgress(message: String) {}
        }

        override fun hold(cents: Int, ref: String, done: (Result) -> Unit) = provider.startPreAuth(cents, ref, callback(done))
        override fun sale(cents: Int, ref: String, done: (Result) -> Unit) = provider.startSale(cents, ref, callback(done))
        override fun complete(ref: String, cents: Int, done: (Boolean) -> Unit) = provider.completeAuth(ref, cents, yesNo(done))
        override fun release(ref: String, done: (Boolean) -> Unit) = provider.voidAuth(ref, yesNo(done))
        override fun reverse(ref: String, cents: Int, done: (Boolean) -> Unit) =
            provider.voidOrRefund(ref, cents) { ok, _ -> done(ok) }
    }

    /** Emulator only (see VendingViewModel.simulated): approves after a moment unless told to decline. */
    class Simulated : VendPayment {
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
    }
}
