package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Settles card transactions left PENDING -- written before the bank call,
 * never moved on because the app died (crash, power, kill) between the
 * payment app's answer and our own status write.
 *
 * Every card flow writes PAID *before* it dispenses (wash, Timer), so a sale
 * the payment app approved but that is still PENDING here was charged and
 * never served: it is reversed automatically. One the payment app never
 * approved becomes DECLINED. Runs only when no customer payment can be in
 * flight -- app start and just before the 03:30 settle -- never on a
 * periodic timer that could collide with a live sale on the payment channel.
 */
object PendingResolver {
    private const val TAG = "PendingResolver"
    private const val MIN_AGE_MS = 5 * 60_000L          // longer than any sale (card timeout <= ~3 min)
    private const val GIVE_UP_MS = 24 * 60 * 60_000L
    private const val CALL_TIMEOUT_MS = 90_000L
    private const val PREFS = "pending_resolver"
    private const val KEY_ALERTED = "alerted_refs"
    private val CARD_METHODS = setOf("CREDIT_CARD", "DEBIT_CARD")

    data class Summary(val checked: Int, val reversed: Int, val declined: Int, val unresolved: Int)

    private val mutex = Mutex()

    suspend fun resolve(context: Context, now: Long = System.currentTimeMillis()): Summary = mutex.withLock {
        val db = LocalDatabase.getInstance(context)
        val stale = withContext(Dispatchers.IO) { db.orderDao().getAll() }
            .filter { it.status == "PENDING" && it.paymentMethod in CARD_METHODS && now - it.createdAt >= MIN_AGE_MS }
        if (stale.isEmpty()) return@withLock Summary(0, 0, 0, 0)

        val sn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN"
        val provider = PaymentProviderFactory.getPaymentProvider(context, DeviceRepository.getPersistedHardwareVendor())
        var reversed = 0
        var declined = 0
        var unresolved = 0
        Log.i(TAG, "${stale.size} stale PENDING card transaction(s) to resolve")

        for (order in stale) {
            val ref = order.ecrRefNum
            when (val r = query(provider, ref)) {
                is IPaymentProvider.QueryResult.Approved -> {
                    // Charged, never served: give the money back.
                    val amount = r.amountCents ?: order.amountCents
                    val (ok, method) = reverse(provider, ref, amount)
                    if (ok) {
                        TransactionRepository.updatePaymentStatus(context, ref, if (method == "REFUND") "REFUNDED" else "VOIDED")
                        DiagnosticManager.reportError(sn, "PENDING_APPROVED_REVERSED", severity = "WARNING", trace = "$ref $amount cents via $method")
                        reversed++
                    } else {
                        // Stop re-trying the reversal on every start; a person must look.
                        TransactionRepository.updatePaymentStatus(context, ref, "PAID")
                        DiagnosticManager.reportError(sn, "PENDING_APPROVED_REVERSAL_FAILED", severity = "CRITICAL", trace = "$ref $amount cents")
                        unresolved++
                    }
                }
                IPaymentProvider.QueryResult.NotFound -> {
                    TransactionRepository.updatePaymentStatus(context, ref, "DECLINED")
                    declined++
                }
                is IPaymentProvider.QueryResult.Error -> {
                    unresolved++
                    if (now - order.createdAt >= GIVE_UP_MS && markAlerted(context, ref)) {
                        DiagnosticManager.reportError(sn, "PENDING_UNRESOLVED", severity = "CRITICAL", trace = "$ref: ${r.message}")
                    }
                }
            }
        }
        Summary(stale.size, reversed, declined, unresolved).also { Log.i(TAG, "Resolved: $it") }
    }

    private suspend fun query(provider: IPaymentProvider, ref: String): IPaymentProvider.QueryResult {
        val done = CompletableDeferred<IPaymentProvider.QueryResult>()
        provider.queryTransaction(ref) { done.complete(it) }
        return withTimeoutOrNull(CALL_TIMEOUT_MS) { done.await() } ?: IPaymentProvider.QueryResult.Error("query timed out")
    }

    private suspend fun reverse(provider: IPaymentProvider, ref: String, amountCents: Int): Pair<Boolean, String> {
        val done = CompletableDeferred<Pair<Boolean, String>>()
        provider.voidOrRefund(ref, amountCents) { ok, method -> done.complete(ok to method) }
        return withTimeoutOrNull(CALL_TIMEOUT_MS * 2) { done.await() } ?: (false to "TIMEOUT")
    }

    /** True the first time a ref is alerted on, so a stuck row doesn't page every start. */
    private fun markAlerted(context: Context, ref: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_ALERTED, emptySet()) ?: emptySet()
        if (ref in seen) return false
        prefs.edit().putStringSet(KEY_ALERTED, seen + ref).apply()
        return true
    }
}
