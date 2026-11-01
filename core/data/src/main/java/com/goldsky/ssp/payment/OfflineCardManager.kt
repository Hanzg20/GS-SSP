package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Offline card sales on Iris / Ourea (docs/migrations/2026-11-01_offline_card.sql).
 *
 * Off unless a manager turns it on for this terminal (Settings), with a limit per
 * sale and a limit on how many may wait for upload. Then a card sale taken while
 * the terminal has no internet is sent to the payment app as offline-allowed
 * (PAYWizard "Deferred"): it can be approved without the bank and is sent later.
 * The store carries the risk -- a card the bank refuses at upload was never paid.
 *
 * The sales waiting for upload are kept here (ref, amount, when) and sent with
 * [upload] as soon as the network is back (Iris / Ourea try every minute) and
 * before a batch close; each comes back UPLOADED or DECLINED on its transaction.
 */
object OfflineCardManager {
    private const val TAG = "OfflineCard"
    private const val PREFS = "offline_card"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LIMIT = "limit_cents"
    private const val KEY_MAX = "max_count"
    private const val KEY_PENDING = "pending"
    const val DEFAULT_LIMIT_CENTS = 5000
    const val DEFAULT_MAX_COUNT = 10
    /** A sale still unclassified after this long is reported for a person to check. */
    private const val STUCK_MS = 48 * 3600_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Serializable data class Pending(val ref: String, val amountCents: Int, val at: Long)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun limitCents(context: Context) = prefs(context).getInt(KEY_LIMIT, DEFAULT_LIMIT_CENTS)
    fun maxCount(context: Context) = prefs(context).getInt(KEY_MAX, DEFAULT_MAX_COUNT)

    fun configure(context: Context, enabled: Boolean, limitCents: Int, maxCount: Int) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_LIMIT, limitCents.coerceIn(100, 100_000)).putInt(KEY_MAX, maxCount.coerceIn(1, 100)).apply()
    }

    fun pending(context: Context): List<Pending> =
        prefs(context).getString(KEY_PENDING, null)?.let { runCatching { json.decodeFromString(ListSerializer(Pending.serializer()), it) }.getOrNull() }
            ?: emptyList()

    private fun savePending(context: Context, list: List<Pending>) {
        prefs(context).edit().putString(KEY_PENDING, json.encodeToString(ListSerializer(Pending.serializer()), list)).apply()
    }

    /**
     * Whether this sale goes out offline-allowed: turned on, the provider can, no
     * internet right now, within the per-sale limit and the count limit.
     */
    fun shouldAllowOffline(context: Context, provider: IPaymentProvider, amountCents: Int): Boolean =
        decide(isEnabled(context), provider.supportsOffline(), isNetworkAvailable(context), amountCents,
            limitCents(context), pending(context).size, maxCount(context))

    internal fun decide(enabled: Boolean, supported: Boolean, online: Boolean, amountCents: Int, limitCents: Int, waiting: Int, max: Int) =
        enabled && supported && !online && amountCents in 1..limitCents && waiting < max

    /** Call when an offline-allowed sale was approved. */
    fun approved(context: Context, ref: String, amountCents: Int) {
        savePending(context, pending(context).filterNot { it.ref == ref } + Pending(ref, amountCents, System.currentTimeMillis()))
        Log.w(TAG, "Offline sale approved, waiting for upload: $ref ($amountCents)")
    }

    data class UploadResult(val uploaded: Int, val declined: List<String>, val stillWaiting: Int, val message: String? = null)

    /** Which waiting sales an upload answer settles: (uploaded, declined). */
    internal fun classify(waiting: List<Pending>, answer: IPaymentProvider.OfflineUpload.Done): Pair<List<Pending>, List<Pending>> {
        val declined = waiting.filter { it.ref in answer.declined }
        val uploaded = if (answer.allSent) waiting - declined.toSet()
                       else waiting.filter { it.ref in answer.uploaded }
        return uploaded to declined
    }

    /**
     * Sends the waiting offline sales (nothing to do when there are none or there's no
     * network) and records each outcome. A refused card: the sale becomes DECLINED and
     * a CRITICAL is reported so the store follows up with the customer.
     */
    suspend fun upload(context: Context, provider: IPaymentProvider): UploadResult = mutex.withLock {
        val waiting = pending(context)
        if (waiting.isEmpty()) return@withLock UploadResult(0, emptyList(), 0)
        if (!isNetworkAvailable(context)) return@withLock UploadResult(0, emptyList(), waiting.size, "offline")
        val done = CompletableDeferred<IPaymentProvider.OfflineUpload>()
        provider.uploadOffline { done.complete(it) }
        val answer = withTimeoutOrNull(120_000) { done.await() } ?: IPaymentProvider.OfflineUpload.Failed("No answer")
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN"
        when (answer) {
            is IPaymentProvider.OfflineUpload.Done -> {
                val (sent, refused) = classify(waiting, answer)
                withContext(Dispatchers.IO) {
                    sent.forEach { TransactionRepository.updateOfflineStatus(context, it.ref, "UPLOADED") }
                    refused.forEach {
                        TransactionRepository.updateOfflineStatus(context, it.ref, "DECLINED")
                        DiagnosticManager.reportError(sn, "OFFLINE_CARD_DECLINED: ${it.ref} ${it.amountCents}c approved offline, refused by the bank at upload", "CRITICAL")
                    }
                }
                val left = waiting - sent.toSet() - refused.toSet()
                savePending(context, left)
                val stuck = left.filter { System.currentTimeMillis() - it.at > STUCK_MS }
                if (left.isNotEmpty()) {
                    // The answer didn't say what became of these: keep them, and keep the raw answer for a person.
                    DiagnosticManager.reportError(sn, "OFFLINE_CARD_UNCLEAR: ${left.size} offline sale(s) not confirmed by the upload", if (stuck.isNotEmpty()) "CRITICAL" else "WARNING", answer.raw.take(2000))
                }
                Log.i(TAG, "Upload: ${sent.size} sent, ${refused.size} refused, ${left.size} unclear")
                UploadResult(sent.size, refused.map { it.ref }, left.size)
            }
            is IPaymentProvider.OfflineUpload.Failed -> {
                Log.w(TAG, "Upload failed: ${answer.message}")
                UploadResult(0, emptyList(), waiting.size, answer.message)
            }
            IPaymentProvider.OfflineUpload.Unsupported -> UploadResult(0, emptyList(), waiting.size, "unsupported")
        }
    }
}
