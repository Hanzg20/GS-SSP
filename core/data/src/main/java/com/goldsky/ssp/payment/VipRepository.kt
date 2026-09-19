package com.goldsky.ssp.payment

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
private data class CardUidParams(val p_card_uid: String)

@Serializable
private data class QrCodeParams(val p_qr_code: String)

@Serializable
private data class GetVipCardResult(
    val found: Boolean,
    val card_uid: String? = null,
    val balance_cents: Int? = null,
    val is_active: Boolean? = null,
    val tier: String? = null
)

@Serializable
private data class ResolveCardUidResult(val card_uid: String? = null)

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
     * Verifies if a VIP card exists. Was a plain table SELECT until
     * 2026-08-29 -- `vip_cards`' only device-facing RLS policy is org-scoped
     * (`org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id =
     * auth.uid())`), which returns nothing for a device not yet assigned to
     * an org, the exact same masked gap `products`/`devices` had (see
     * docs/system_architecture.md v2.32/v2.34/v2.35) -- but unlike those two,
     * this couldn't just get a blanket `is_anonymous` SELECT policy: a
     * customer's card balance isn't a public catalog, and RLS can't enforce
     * "only when queried by a specific card_uid" -- any policy that makes a
     * row visible at all makes it visible to a client that queries without
     * that filter too. Routed through a SECURITY DEFINER RPC instead (same
     * pattern [deductBalance] already used), which accepts the specific uid
     * and returns only that one match -- no table-level SELECT grant needed
     * at all now for this lookup.
     */
    suspend fun getVipCard(uid: String): VipCard? = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClientProvider.client.postgrest.rpc(
                "get_vip_card_by_uid",
                CardUidParams(p_card_uid = uid)
            )
            val decoded = result.decodeAs<GetVipCardResult>()
            if (!decoded.found) null
            else VipCard(
                card_uid = decoded.card_uid ?: uid,
                balance_cents = decoded.balance_cents ?: 0,
                is_active = decoded.is_active ?: false,
                // Falls back to VipCard's own "REGULAR" default if the RPC's
                // tier key is somehow absent -- matches the exact same silent
                // behavior a card really did have before the tier column
                // existed at all (see the ALTER TABLE comment in
                // docs/supabase_full_schema.sql), so an old/un-migrated
                // database degrades to today's behavior, not a crash.
                tier = decoded.tier ?: "REGULAR"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch VIP card: ${e.message}")
            null
        }
    }

    /**
     * Resolves a 12-character member QR code (see
     * docs/coupon_redemption_integration.md §2.1) to the card_uid the rest
     * of the VIP flow (deductBalance, initVipPayment) actually operates on.
     * Same RPC-not-table-SELECT reasoning as [getVipCard]'s doc comment --
     * this was a plain SELECT until 2026-08-29 despite that function's old
     * comment claiming "a plain SELECT is fine here... no race to guard
     * against" -- true for the race concern, but that was never actually the
     * reason to avoid a table policy; the masked-org-gap and no-enumeration
     * reasons apply here identically to [getVipCard].
     */
    suspend fun resolveCardUidByQrCode(qrCode: String): String? = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClientProvider.client.postgrest.rpc(
                "resolve_vip_card_uid_by_qr",
                QrCodeParams(p_qr_code = qrCode)
            )
            result.decodeAs<ResolveCardUidResult>().card_uid
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
