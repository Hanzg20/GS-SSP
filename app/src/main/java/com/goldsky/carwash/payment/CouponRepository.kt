package com.goldsky.carwash.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
private data class RedeemCouponParams(
    val p_code: String,
    val p_device_sn: String
)

@Serializable
private data class RedeemCouponResult(
    val success: Boolean,
    val type: String? = null,
    val value: Int? = null,
    val applicable_product_id: String? = null,
    val message: String? = null
)

/**
 * Outcome of [CouponRepository.redeemCoupon]. Mirrors [VipDeductResult]'s
 * split of "server said no" vs "we couldn't even reach the server" -- the
 * caller shows one generic "this coupon can't be used" toast for [Rejected]
 * regardless of reason (see docs/coupon_redemption_integration.md §4.6: not_
 * found/already_used/wrong_org must not be distinguishable to the customer),
 * but [NetworkError] is worth telling the customer to try scanning again.
 */
sealed class CouponRedeemResult {
    /** [value]'s unit depends on [type]: PERCENT_OFF is 0-100, FIXED_OFF (including compensation) is cents, FREE_WASH ignores it entirely. */
    data class Success(val type: String, val value: Int, val applicableProductId: String?) : CouponRedeemResult()
    data class Rejected(val reason: String) : CouponRedeemResult() // not_found | inactive | expired | already_used | wrong_org | device_not_registered
    object NetworkError : CouponRedeemResult()
}

private const val TAG = "CouponRepository"

/**
 * Repository for coupon/promotion/compensation-voucher redemption, backed by
 * the server-side redeem_coupon() RPC (see docs/supabase_full_schema.sql).
 * The check (exists/active/not expired/not used up/right tenant) and the
 * uses_count increment happen atomically in Postgres under a row lock --
 * this must NOT be re-implemented as a client-side read-then-UPDATE, for the
 * same reason deduct_vip_balance() isn't: the anon/authenticated key
 * embedded in BuildConfig gives any extracted APK direct table access
 * otherwise, and reads-then-writes race against concurrent redemptions.
 */
object CouponRepository {

    suspend fun redeemCoupon(code: String, deviceSn: String): CouponRedeemResult = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClientProvider.client.postgrest.rpc(
                "redeem_coupon",
                RedeemCouponParams(p_code = code, p_device_sn = deviceSn)
            )
            val decoded = result.decodeAs<RedeemCouponResult>()
            if (decoded.success) {
                CouponRedeemResult.Success(
                    type = decoded.type ?: "FIXED_OFF",
                    value = decoded.value ?: 0,
                    applicableProductId = decoded.applicable_product_id
                )
            } else {
                Log.w(TAG, "Redeem rejected: ${decoded.message}")
                CouponRedeemResult.Rejected(decoded.message ?: "unknown")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Redeem RPC error (network/transport): ${e.message}")
            CouponRedeemResult.NetworkError
        }
    }
}
