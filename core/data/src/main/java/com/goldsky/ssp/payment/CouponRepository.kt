package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
private data class CheckParams(val p_code: String)

@Serializable
private data class RedeemParams(val p_code: String, val p_ref: String?)

@Serializable
private data class ReleaseParams(val p_redemption_id: String, val p_reason: String?)

@Serializable
private data class CouponAnswer(
    val success: Boolean,
    val type: String? = null,
    val value: Int? = null,
    val applicable_product_id: String? = null,
    val expires_at: String? = null,
    val redemption_id: String? = null,
    val message: String? = null
)

/**
 * Outcome of [CouponRepository.redeemCoupon]. Every refusal is the same word from the
 * server ("unavailable", 2026-11-08) -- the customer gets one "this coupon can't be used";
 * [NetworkError] is worth telling the customer to try again.
 */
sealed class CouponRedeemResult {
    /**
     * [value]'s unit depends on [type]: PERCENT_OFF is 0-100, FIXED_OFF (including
     * compensation) is cents, FREE_WASH ignores it. The coupon is USED from here on:
     * [redemptionId] gives it back through [CouponRepository.releaseCoupon] if the sale
     * does not go through (card declined / cancelled, the service did not start).
     */
    data class Success(val type: String, val value: Int, val applicableProductId: String?, val redemptionId: String?) : CouponRedeemResult()
    data class Rejected(val reason: String) : CouponRedeemResult()
    object NetworkError : CouponRedeemResult()
}

/**
 * Outcome of [CouponRepository.peekCoupon]. A [Success] here does NOT mean the coupon has
 * been used -- only [CouponRepository.redeemCoupon] does that.
 */
sealed class CouponPeekResult {
    data class Success(val type: String, val value: Int, val applicableProductId: String?, val expiresAt: String?) : CouponPeekResult()
    data class Rejected(val reason: String) : CouponPeekResult()
    object NetworkError : CouponPeekResult()
}

private const val TAG = "CouponRepository"

/**
 * Coupons through the server (docs/migrations/2026-11-08_coupon_redeem_release.sql):
 * coupon_check (look only), coupon_redeem (use, before the card is asked for) and
 * coupon_release (give back when the sale does not go through). The server takes the
 * terminal's SN from its own linked session, not from the app; a session that isn't linked
 * ("device_not_linked") is re-linked once and the call repeated. The deviceSn parameters
 * stay only so the callers did not have to change.
 */
object CouponRepository {

    private suspend inline fun <reified P : Any> call(fn: String, params: P): CouponAnswer {
        repeat(2) { attempt ->
            val answer = SupabaseClientProvider.client.postgrest.rpc(fn, params).decodeAs<CouponAnswer>()
            if (answer.message != "device_not_linked" || attempt == 1) return answer
            Log.w(TAG, "$fn: session not linked to the device, re-linking")
            SupabaseClientProvider.relinkDeviceIdentity()
        }
        error("unreachable")
    }

    /** Uses the coupon now (atomically, server-side). [ref] = the sale it goes to (ecr_ref_num), if known. */
    suspend fun redeemCoupon(code: String, @Suppress("UNUSED_PARAMETER") deviceSn: String, ref: String? = null): CouponRedeemResult =
        withContext(Dispatchers.IO) {
            try {
                val a = call("coupon_redeem", RedeemParams(code, ref))
                if (a.success) {
                    CouponRedeemResult.Success(a.type ?: "FIXED_OFF", a.value ?: 0, a.applicable_product_id, a.redemption_id)
                } else {
                    Log.w(TAG, "Redeem refused: ${a.message}")
                    CouponRedeemResult.Rejected(a.message ?: "unavailable")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Redeem RPC error (network/transport): ${e.message}")
                CouponRedeemResult.NetworkError
            }
        }

    /** Look only -- the coupon is not used. */
    suspend fun peekCoupon(code: String, @Suppress("UNUSED_PARAMETER") deviceSn: String): CouponPeekResult =
        withContext(Dispatchers.IO) {
            try {
                val a = call("coupon_check", CheckParams(code))
                if (a.success) {
                    CouponPeekResult.Success(a.type ?: "FIXED_OFF", a.value ?: 0, a.applicable_product_id, a.expires_at)
                } else {
                    Log.w(TAG, "Check refused: ${a.message}")
                    CouponPeekResult.Rejected(a.message ?: "unavailable")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Check RPC error (network/transport): ${e.message}")
                CouponPeekResult.NetworkError
            }
        }

    /**
     * Gives a used coupon back (same terminal, within 2 hours). Tried twice; if it still
     * fails the coupon stays used -- reported, a person can issue a new one. Never throws.
     */
    suspend fun releaseCoupon(redemptionId: String?, reason: String, deviceSn: String): Boolean = withContext(Dispatchers.IO) {
        if (redemptionId == null) return@withContext false
        repeat(2) {
            try {
                val a = call("coupon_release", ReleaseParams(redemptionId, reason))
                if (a.success) {
                    Log.i(TAG, "Coupon given back ($reason)")
                    return@withContext true
                }
                Log.w(TAG, "Release refused: ${a.message}")
                return@repeat
            } catch (e: Exception) {
                Log.e(TAG, "Release RPC error: ${e.message}")
            }
        }
        DiagnosticManager.reportError(deviceSn, "COUPON_RELEASE_FAILED", severity = "WARNING", trace = "redemption=$redemptionId reason=$reason")
        false
    }
}
