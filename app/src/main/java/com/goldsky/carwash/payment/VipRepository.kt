package com.goldsky.carwash.payment

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

/**
 * Repository using direct REST calls to Supabase for VIP membership data.
 */
object VipRepository {
    private const val SUPABASE_URL = "https://your-project-id.supabase.co"
    private const val SUPABASE_KEY = "your-anon-key"

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
            val response: List<VipCard> = client.get("$SUPABASE_URL/rest/v1/vip_cards") {
                header("apikey", SUPABASE_KEY)
                header("Authorization", "Bearer $SUPABASE_KEY")
                parameter("card_uid", "eq.$uid")
            }.body()
            response.firstOrNull()
        } catch (e: Exception) {
            android.util.Log.e("VipRepository", "REST Error: ${e.message}")
            null
        }
    }

    /**
     * Deducts balance via PATCH request.
     */
    suspend fun deductBalance(uid: String, amountInCents: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val amount = amountInCents / 100.0
            val card = getVipCard(uid)
            if (card != null && card.balance >= amount && card.is_active) {
                val newBalance = card.balance - amount
                client.patch("$SUPABASE_URL/rest/v1/vip_cards") {
                    header("apikey", SUPABASE_KEY)
                    header("Authorization", "Bearer $SUPABASE_KEY")
                    header("Content-Type", "application/json")
                    header("Prefer", "return=representation")
                    parameter("card_uid", "eq.$uid")
                    setBody(mapOf("balance" to newBalance))
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("VipRepository", "Deduct Error: ${e.message}")
            false
        }
    }
}
