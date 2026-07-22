package com.goldsky.carwash.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class QrPaymentSession(
    val tx_id: String,
    val device_sn: String,
    val amount_cents: Int,
    val status: String = "PENDING"
)

@Serializable
private data class QrSessionStatus(val status: String)

/**
 * Backs the "scan-to-pay" flow with a real Supabase-persisted session instead
 * of a client-side fake timer. A gateway webhook (Alipay/WeChat/Stripe --
 * external integration, needs merchant credentials this repo doesn't have)
 * is expected to flip `status` to PAID once it confirms funds; this class
 * only creates the session and polls it, never marks it paid itself.
 */
object QrPaymentRepository {
    private const val TAG = "QrPaymentRepository"

    suspend fun createSession(txId: String, deviceSn: String, amountCents: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                SupabaseClientProvider.client.postgrest["qr_payment_sessions"]
                    .insert(QrPaymentSession(tx_id = txId, device_sn = deviceSn, amount_cents = amountCents))
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create QR session: ${e.message}")
                false
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
