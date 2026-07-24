package com.goldsky.carwash.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.call.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
private data class CreateSessionRequest(
    val tx_id: String,
    val device_sn: String,
    val amount_cents: Int
)

@Serializable
private data class CreateSessionResponse(val code_url: String)

@Serializable
private data class QrSessionStatus(val status: String)

/**
 * Backs the "scan-to-pay" flow with a real Supabase-persisted session instead
 * of a client-side fake timer. Session creation goes through the
 * create-qr-session Edge Function (supabase/functions/create-qr-session),
 * which is the only place that ever talks to a payment gateway -- gateway
 * secret keys must never reach the APK. A gateway webhook
 * (supabase/functions/payment-webhook) is the only thing that flips
 * `status` to PAID; this class only creates the session and polls it.
 */
object QrPaymentRepository {
    private const val TAG = "QrPaymentRepository"

    /**
     * Not backed by a background offline queue like TransactionRepository's
     * audit writes -- the customer is standing at the kiosk waiting for a QR
     * code right now, so a failure here has to surface immediately rather
     * than being silently retried after they've already walked away. It
     * does get a few quick in-band retries (matching DeviceRepository's
     * pattern) since transient network blips are common and worth absorbing
     * before making the customer tap "try again" themselves.
     *
     * Returns the code_url to render as a QR code, or null on failure. Until
     * a real gateway is wired in (see supabase/functions/_shared/gateways),
     * the Edge Function's stub gateway returns an unpayable placeholder URL
     * -- sessions will always time out, which is expected pre-launch.
     */
    suspend fun createSession(txId: String, deviceSn: String, amountCents: Int): String? =
        withContext(Dispatchers.IO) {
            try {
                retryWithBackoff(times = 3) {
                    val response = SupabaseClientProvider.invokeFunction(
                        "create-qr-session",
                        CreateSessionRequest(tx_id = txId, device_sn = deviceSn, amount_cents = amountCents)
                    )
                    if (!response.status.isSuccess()) {
                        throw IllegalStateException("create-qr-session failed: ${response.status}")
                    }
                    response.body<CreateSessionResponse>().code_url
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create QR session: ${e.message}")
                null
            }
        }

    /**
     * Polls the session status until PAID/CANCELLED/EXPIRED or [maxAttempts]
     * is reached. Returns true only if the backend actually marked it PAID --
     * never fabricates success locally.
     */
    suspend fun pollUntilPaid(txId: String, maxAttempts: Int = 60, intervalMs: Long = 2000): Boolean =
        withContext(Dispatchers.IO) {
            repeat(maxAttempts) {
                try {
                    val session = SupabaseClientProvider.client.postgrest["qr_payment_sessions"]
                        .select { filter { eq("tx_id", txId) } }
                        .decodeSingleOrNull<QrSessionStatus>()
                    when (session?.status) {
                        "PAID" -> return@withContext true
                        "CANCELLED", "EXPIRED" -> return@withContext false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Poll attempt failed: ${e.message}")
                }
                delay(intervalMs)
            }
            Log.w(TAG, "Polling timed out for $txId after $maxAttempts attempts")
            false
        }
}
