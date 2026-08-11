package com.goldsky.carwash.dispense.adapter

import android.util.Log
import com.goldsky.carwash.dispense.*
import com.goldsky.carwash.payment.SupabaseClientProvider
import com.goldsky.carwash.payment.hardware.ISerialProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
private data class DeviceCommandStatus(val status: String)

/**
 * Delegates the actual relay pulse to a separate GS-EdgeNexus gateway
 * device via CMP (Supabase `device_commands`), instead of driving
 * [serialProvider]/local hardware directly like [PulseCreditAdapter]/
 * [SingleCommandAdapter] do. The payment-webhook Edge Function
 * (supabase/functions/payment-webhook) is what actually inserts the
 * `device_commands` row once a QR/Stripe payment is confirmed PAID -- this
 * adapter's only job is to find that row (matched by [DispenseJob.txRef],
 * which for the QR path is the same tx_id the webhook used -- see
 * MainActivity.startFinalizationSequence's qrTxId parameter) and wait for
 * EdgeNexus to report it COMPLETED/FAILED.
 *
 * Lab-test scope: proves the App -> CMP -> EdgeNexus -> CMP -> App round
 * trip works end to end. The webhook currently hardcodes which EdgeNexus
 * device/bay handles every payment (no kiosk->gateway mapping exists in
 * the schema yet) -- this adapter doesn't need to know that target itself,
 * it only watches the command row the webhook already created.
 */
class EdgeNexusRemoteAdapter : IDispenseAdapter {
    companion object {
        private const val TAG = "EdgeNexusRemoteAdapter"
        private const val POLL_INTERVAL_MS = 2000L
        private const val MAX_ATTEMPTS = 60 // ~2 minutes, matching QrPaymentRepository.pollUntilPaid's budget
    }

    override suspend fun dispense(
        job: DispenseJob,
        ackStrategy: IAckStrategy,
        serialProvider: ISerialProvider,
        onProgress: (Int, Int) -> Unit
    ): DispenseOutcome {
        onProgress(0, 1)
        repeat(MAX_ATTEMPTS) {
            try {
                val row = SupabaseClientProvider.client.postgrest["device_commands"]
                    .select { filter { eq("payload->>tx_id", job.txRef) } }
                    .decodeSingleOrNull<DeviceCommandStatus>()

                when (row?.status) {
                    "COMPLETED" -> {
                        onProgress(1, 1)
                        return DispenseOutcome.Confirmed("EdgeNexus reported COMPLETED")
                    }
                    "FAILED" -> {
                        onProgress(1, 1)
                        return DispenseOutcome.Failed("EdgeNexus reported FAILED")
                    }
                    // null (row not visible/found yet) or PENDING -- keep polling.
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll attempt failed: ${e.message}")
            }
            delay(POLL_INTERVAL_MS)
        }

        onProgress(1, 1)
        Log.w(TAG, "Timed out waiting for EdgeNexus to complete tx_id=${job.txRef}")
        return DispenseOutcome.Failed("EdgeNexus did not report completion in time")
    }
}
