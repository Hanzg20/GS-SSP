package com.goldsky.carwash.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class VipCard(
    val card_uid: String,
    val balance_cents: Int,
    val is_active: Boolean = true,
    val tier: String = "REGULAR" // "REGULAR", "GOLD", "PLATINUM"
)

@Serializable
private data class DeductBalanceParams(
    val p_card_uid: String,
    val p_amount_cents: Int
)

@Serializable
private data class DeductBalanceResult(
    val success: Boolean,
    val new_balance_cents: Int? = null,
    val message: String? = null
)

/**
 * Outcome of [VipRepository.deductBalance]. A plain Boolean previously
 * collapsed "the bank/network call failed" and "the card legitimately has no
 * money on it" into the same false -- MainActivity showed "Balance
 * Insufficient" for both, which is simply wrong when the real cause was a
 * dropped connection (the customer's card may have had plenty of balance).
 * Deliberately NOT retried via a background queue like TransactionRepository
 * does for audit writes: this RPC has no idempotency key, so blindly
 * replaying it after a network failure risks deducting twice if the first
 * attempt actually reached the server and only the response was lost. The
 * safe, honest thing to do on NetworkError is tell the customer to try
 * tapping again, not to silently retry behind their back.
 */
sealed class VipDeductResult {
    data class Success(val newBalanceCents: Int) : VipDeductResult()
    data class Rejected(val reason: String) : VipDeductResult() // insufficient_balance | card_not_found | card_inactive | invalid_amount
    object NetworkError : VipDeductResult()
}

/**
 * Pure mapping from the RPC's decoded response fields to [VipDeductResult],
 * pulled out of [VipRepository.deductBalance] so this classification (the
 * actual fix for the Boolean-collapsing bug) is unit-testable without a
 * network stack or Android Context.
 */
internal fun classifyDeductResult(success: Boolean, newBalanceCents: Int?, message: String?): VipDeductResult =
    if (success) {
        VipDeductResult.Success(newBalanceCents ?: 0)
    } else {
        VipDeductResult.Rejected(message ?: "unknown")
    }

private const val TAG = "VipRepository"

/**
 * Repository for VIP membership data, backed by Supabase Postgrest.
 */
object VipRepository {

    /**
     * Verifies if a VIP card exists.
     */
    suspend fun getVipCard(uid: String): VipCard? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.client.postgrest["vip_cards"]
                .select { filter { eq("card_uid", uid) } }
                .decodeSingleOrNull<VipCard>()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch VIP card: ${e.message}")
            null
        }
    }

    /**
     * Resolves a 12-character member QR code (see
     * docs/coupon_redemption_integration.md §2.1) to the card_uid the rest
     * of the VIP flow (deductBalance, initVipPayment) actually operates on.
     * A plain SELECT is fine here (unlike deductBalance) -- this is a
     * read-only lookup with no state change, so there's no race to guard
     * against.
     */
    suspend fun resolveCardUidByQrCode(qrCode: String): String? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.client.postgrest["vip_cards"]
                .select { filter { eq("qr_code", qrCode) } }
                .decodeSingleOrNull<VipCard>()?.card_uid
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve card by QR code: ${e.message}")
            null
        }
    }

    /**
     * Deducts balance via the server-side deduct_vip_balance RPC (see
     * docs/supabase_full_schema.sql). The check (balance/active) and the
     * deduction happen atomically in Postgres, under a row lock -- this must
     * NOT be re-implemented as a client-side read-then-PATCH, since the anon
     * key embedded in BuildConfig gives any extracted APK direct table-write
     * access otherwise.
     */
    suspend fun deductBalance(uid: String, amountInCents: Int): VipDeductResult = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClientProvider.client.postgrest.rpc(
                "deduct_vip_balance",
                DeductBalanceParams(p_card_uid = uid, p_amount_cents = amountInCents)
            )
            val decoded = result.decodeAs<DeductBalanceResult>()
            if (!decoded.success) {
                Log.w(TAG, "Deduct rejected: ${decoded.message}")
            }
            classifyDeductResult(decoded.success, decoded.new_balance_cents, decoded.message)
        } catch (e: Exception) {
            Log.e(TAG, "Deduct RPC error (network/transport): ${e.message}")
            VipDeductResult.NetworkError
        }
    }
}
