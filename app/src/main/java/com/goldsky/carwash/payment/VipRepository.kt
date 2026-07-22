package com.goldsky.carwash.payment

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class VipCard(
    val card_uid: String,
    val balance: Double,
    val is_active: Boolean = true
)

@Serializable
private data class DeductBalanceParams(
    val p_card_uid: String,
    val p_amount_cents: Int
)

@Serializable
private data class DeductBalanceResult(
    val success: Boolean,
    val new_balance: Double? = null,
    val message: String? = null
)

/**
 * Repository using direct REST calls to Supabase for VIP membership data.
 */
object VipRepository {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    /**
     * Verifies if a VIP card exists via REST API.
     */
    suspend fun getVipCard(uid: String): VipCard? = withContext(Dispatchers.IO) {
        try {
            val response: List<VipCard> = client.get("${SupabaseConfig.URL}/rest/v1/vip_cards") {
                header("apikey", SupabaseConfig.KEY)
                header("Authorization", "Bearer ${SupabaseConfig.KEY}")
                parameter("card_uid", "eq.$uid")
            }.body()
            response.firstOrNull()
        } catch (e: Exception) {
            android.util.Log.e("VipRepository", "REST Error: ${e.message}")
            null
        }
    }

    /**
     * Deducts balance via the server-side deduct_vip_balance RPC (see
     * supabase/migrations/0001_vip_deduct_balance_rpc.sql). The check
     * (balance/active) and the deduction happen atomically in Postgres, under
     * a row lock -- this must NOT be re-implemented as a client-side
     * read-then-PATCH, since the anon key embedded in BuildConfig gives any
     * extracted APK direct table-write access otherwise.
     */
    suspend fun deductBalance(uid: String, amountInCents: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = SupabaseClientProvider.client.postgrest.rpc(
                "deduct_vip_balance",
                DeductBalanceParams(p_card_uid = uid, p_amount_cents = amountInCents)
            )
            val decoded = result.decodeAs<DeductBalanceResult>()
            if (!decoded.success) {
                android.util.Log.w("VipRepository", "Deduct rejected: ${decoded.message}")
            }
            decoded.success
        } catch (e: Exception) {
            android.util.Log.e("VipRepository", "Deduct RPC error: ${e.message}")
            false
        }
    }
}
