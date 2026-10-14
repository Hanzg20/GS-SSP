package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One of the store's ready-made discounts (Cael > Catalog > Discounts,
 * docs/migrations/2026-10-14_discounts.sql). [applies_to]: ANY | LINE | ORDER.
 */
@Serializable
data class DiscountPreset(
    val id: String,
    val name: String,
    val percent_bps: Int? = null,
    val amount_cents: Int? = null,
    val applies_to: String = "ANY",
    val requires_manager: Boolean = false,
) {
    fun appliesToLine(): Boolean = applies_to != "ORDER"
    fun appliesToOrder(): Boolean = applies_to != "LINE"

    /** The discount it puts on a line or the order; [approvedBy] when a manager okayed it. */
    fun toDiscount(approvedBy: String? = null) = Discount(
        label = name, percentBps = percent_bps, amountCents = amount_cents, presetId = id, approvedBy = approvedBy,
    )
}

/**
 * The store's discount presets, synced with the catalog and cached so the
 * discount panel works offline. Rules for typed-in discounts that aren't presets
 * live here too, so every terminal applies the same limit.
 */
object DiscountPresetRepository {
    private const val TAG = "DiscountPresets"
    private const val PREFS = "discount_presets"
    private const val KEY_LIST = "list"
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(DiscountPreset.serializer())

    /** A typed-in discount above this needs a manager PIN: more than 10%, or more than $10. */
    const val STAFF_LIMIT_BPS = 1000
    const val STAFF_LIMIT_CENTS = 1000

    private val _presets = MutableStateFlow<List<DiscountPreset>>(emptyList())
    val presets: StateFlow<List<DiscountPreset>> = _presets

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        _presets.value = cached()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cached(): List<DiscountPreset> =
        prefs()?.getString(KEY_LIST, null)?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

    /** True when the cloud answered (the cache now matches it); false keeps the cached list. */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val orgId = DeviceRepository.getPersistedOrgId() ?: return@withContext false
        try {
            val list = SupabaseClientProvider.client.postgrest["discount_presets"]
                .select(Columns.raw("id, name, percent_bps, amount_cents, applies_to, requires_manager")) {
                    filter { eq("org_id", orgId); eq("is_active", true) }
                    order("sort", Order.ASCENDING)
                    order("name", Order.ASCENDING)
                }
                .decodeList<DiscountPreset>()
            prefs()?.edit()?.putString(KEY_LIST, json.encodeToString(listSerializer, list))?.apply()
            _presets.value = list
            Log.i(TAG, "Discount presets synced: ${list.size}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Discount preset sync failed, keeping the cached list: ${e.message}")
            false
        }
    }

    /**
     * Whether a typed-in discount of [discount] on [baseCents] needs a manager:
     * over 10% of what it applies to, or over $10.
     */
    fun needsManager(discount: Discount, baseCents: Int): Boolean {
        val off = discount.offCents(baseCents)
        if (off > STAFF_LIMIT_CENTS) return true
        // A percentage is judged as typed: 10% of $12.95 rounds to $1.30, a hair over 10%.
        discount.percentBps?.let { return it > STAFF_LIMIT_BPS }
        return off.toLong() * 10_000 > baseCents.toLong() * STAFF_LIMIT_BPS
    }

    /** The store moved (DeviceRepository.storeChanges): the old store's presets don't apply. */
    fun forgetStore() {
        prefs()?.edit()?.remove(KEY_LIST)?.apply()
        _presets.value = emptyList()
    }
}
