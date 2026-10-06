package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** A merchant's VIP load plan: pay [amount_cents], get [bonus_cents] extra (vip_load_plans). */
@Serializable
data class VipLoadPlan(val id: String, val amount_cents: Int, val bonus_cents: Int = 0) {
    val totalCents get() = amount_cents + bonus_cents

    companion object {
        /** "$60" for whole dollars, "$60.50" otherwise -- fits the VIP page's narrow rows. */
        fun money(cents: Int): String =
            if (cents % 100 == 0) "$${cents / 100}" else "$%d.%02d".format(cents / 100, cents % 100)
    }
}

sealed class VipLoadResult {
    /** Credited. [created] = a new card was issued; [qrCode] is its member code. */
    data class Loaded(
        val cardUid: String, val qrCode: String?, val balanceCents: Int,
        val loadedCents: Int?, val bonusCents: Int?, val created: Boolean,
    ) : VipLoadResult()

    /** The server answered no (plan/amount/card problem): nothing was credited, the sale must be reversed. */
    data class Rejected(val reason: String) : VipLoadResult()

    /** No answer: the card may or may not have been credited -- never reverse automatically. */
    data class Unreachable(val error: String) : VipLoadResult()
}

/**
 * Terminal side of VIP sales (docs/migrations/2026-10-06_vip_terminal_load.sql).
 * The terminal only takes the card payment; the server checks that sale and
 * credits the card in device_vip_load(), idempotently per sale.
 */
object VipLoadRepository {
    private const val TAG = "VipLoadRepository"

    @Serializable
    private data class LoadParams(
        val p_ecr_ref_num: String,
        val p_plan_id: String,
        val p_card_uid: String? = null,
        val p_mobile_phone: String? = null,
    )

    @Serializable
    private data class LoadResponse(
        val success: Boolean,
        val message: String? = null,
        val card_uid: String? = null,
        val qr_code: String? = null,
        val balance_cents: Int? = null,
        val loaded_cents: Int? = null,
        val bonus_cents: Int? = null,
        val created: Boolean = false,
    )

    /** Active plans for this terminal's merchant; null when they couldn't be fetched. */
    suspend fun getPlans(): List<VipLoadPlan>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.client.postgrest.rpc("get_vip_load_plans").decodeList<VipLoadPlan>()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch VIP load plans: ${e.message}")
            null
        }
    }

    /**
     * Credits the paid sale [ecrRefNum] onto a new card ([cardUid] null) or an
     * existing one. Network errors are retried -- safe, the server answers a
     * repeat with the first result -- a definite "no" is not.
     */
    suspend fun load(ecrRefNum: String, planId: String, cardUid: String?, mobilePhone: String?): VipLoadResult =
        withContext(Dispatchers.IO) {
            var lastError = "unknown"
            repeat(ATTEMPTS) { attempt ->
                try {
                    val r = SupabaseClientProvider.client.postgrest.rpc(
                        "device_vip_load", LoadParams(ecrRefNum, planId, cardUid, mobilePhone)
                    ).decodeAs<LoadResponse>()
                    return@withContext if (r.success && r.card_uid != null) {
                        VipLoadResult.Loaded(r.card_uid, r.qr_code, r.balance_cents ?: 0, r.loaded_cents, r.bonus_cents, r.created)
                    } else {
                        VipLoadResult.Rejected(r.message ?: "unknown")
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "device_vip_load attempt ${attempt + 1} failed: $lastError")
                    if (attempt < ATTEMPTS - 1) delay(RETRY_MS * (attempt + 1))
                }
            }
            VipLoadResult.Unreachable(lastError)
        }

    private const val ATTEMPTS = 4
    private const val RETRY_MS = 2_000L
}
