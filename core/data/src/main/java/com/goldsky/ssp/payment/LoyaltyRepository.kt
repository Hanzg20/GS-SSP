package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Retail loyalty points (docs/migrations/2026-11-01_loyalty.sql): a member is a phone
 * number in the store; points are earned on what was paid before tax, and a reward
 * (reward_points for reward_cents off) is an order discount. The store turns it on
 * and sets the rules in Cael; the terminal keeps the last rules it saw, so the
 * cashier still sees them offline.
 *
 * Looking up / joining needs the network. Recording a sale's points is queued when
 * it can't reach the server ("member_sale"), behind the sale's own writes -- the
 * server reads the amount from the PAID sale itself and records each sale once.
 */
object LoyaltyRepository {
    private const val TAG = "Loyalty"
    private const val PREFS = "loyalty"
    private const val KEY_SETTINGS = "settings"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable data class Settings(
        val enabled: Boolean = false,
        val points_per_dollar: Int = 1,
        val reward_points: Int = 100,
        val reward_cents: Int = 500
    )

    @Serializable data class Member(
        val id: String,
        val phone: String,
        val name: String? = null,
        val points: Int = 0,
        val lifetime_points: Int = 0,
        val visits: Int = 0
    ) {
        /** "Amy" or the last 4 digits. */
        val label: String get() = name?.takeIf { it.isNotBlank() } ?: ("…" + phone.takeLast(4))
    }

    @Serializable private data class LookupResult(val found: Boolean = false, val member: Member? = null)
    @Serializable private data class JoinResult(val success: Boolean = false, val message: String? = null, val member: Member? = null)
    @Serializable data class SaleResult(
        val success: Boolean = false, val message: String? = null,
        val earned: Int = 0, val redeemed: Int = 0, val member: Member? = null
    )
    @Serializable private data class PhoneParams(val p_phone: String)
    @Serializable private data class JoinParams(val p_phone: String, val p_name: String?)
    @Serializable private data class SaleParams(val p_member_id: String, val p_ecr_ref_num: String, val p_redeem_points: Int, val p_staff_name: String?)

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The store's rules as last seen (off until fetched once). */
    fun settings(): Settings =
        prefs()?.getString(KEY_SETTINGS, null)?.let { runCatching { json.decodeFromString(Settings.serializer(), it) }.getOrNull() } ?: Settings()

    /** Fetches the rules; keeps the last ones when offline. */
    suspend fun refreshSettings(): Settings = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val s = SupabaseClientProvider.client.postgrest.rpc("member_settings").decodeAs<Settings>()
            prefs()?.edit()?.putString(KEY_SETTINGS, json.encodeToString(Settings.serializer(), s))?.apply()
            s
        } catch (e: Exception) {
            Log.w(TAG, "Settings refresh failed: ${e.message}"); settings()
        }
    }

    /** Digits only, a North American leading 1 dropped -- the server's member_phone(). */
    fun normalizePhone(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return if (d.length == 11 && d.startsWith("1")) d.drop(1) else d
    }

    fun validPhone(raw: String): Boolean = normalizePhone(raw).length in 7..15

    /** Result.success(null) = no such member; failure = unreachable. */
    suspend fun lookup(phone: String): Result<Member?> = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            Result.success(SupabaseClientProvider.client.postgrest.rpc("member_lookup", PhoneParams(phone)).decodeAs<LookupResult>().member)
        } catch (e: Exception) {
            Log.w(TAG, "Lookup failed: ${e.message}"); Result.failure(e)
        }
    }

    /** The new member, or the server's reason ("bad_phone", "loyalty_off"); message null = unreachable. */
    suspend fun join(phone: String, name: String?): Pair<Member?, String?> = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val r = SupabaseClientProvider.client.postgrest.rpc("member_join", JoinParams(phone, name?.trim()?.ifBlank { null })).decodeAs<JoinResult>()
            if (r.success) r.member to null else null to (r.message ?: "refused")
        } catch (e: Exception) {
            Log.w(TAG, "Join failed: ${e.message}"); null to null
        }
    }

    /**
     * How many points a reward takes and what it's worth for a sale of [netCents] (after
     * other discounts, before tax): as many whole rewards as the member has points for,
     * but never more than the sale. (0, 0) when none fits.
     */
    fun reward(points: Int, netCents: Int, s: Settings = settings()): Pair<Int, Int> {
        if (!s.enabled || s.reward_points <= 0 || s.reward_cents <= 0 || points < s.reward_points || netCents <= 0) return 0 to 0
        val n = minOf(points / s.reward_points, netCents / s.reward_cents)
        return (n * s.reward_points) to (n * s.reward_cents)
    }

    /** Points a sale of [netCents] (before tax) earns: per whole dollar. */
    fun earnFor(netCents: Int, s: Settings = settings()): Int = if (s.enabled) (maxOf(netCents, 0) / 100) * s.points_per_dollar else 0

    /**
     * After the sale is PAID: its points (and the reward's points taken). null = queued
     * (unreachable, or the server doesn't see the sale PAID yet).
     */
    suspend fun recordSale(context: Context, memberId: String, ecrRefNum: String, redeemPoints: Int): SaleResult? = withContext(Dispatchers.IO) {
        val r = recordSaleRemote(memberId, ecrRefNum, redeemPoints, StaffRepository.current?.name)
        if (r == null) {
            OfflineQueueManager.enqueue(context.filesDir, PendingOp(type = "member_sale", orderId = memberId, ecrRefNum = ecrRefNum,
                amountCents = redeemPoints, approverName = StaffRepository.current?.name))
            Log.w(TAG, "Member sale queued: $ecrRefNum")
        }
        r
    }

    /** null = retry later (unreachable / not PAID on the server yet); otherwise final. */
    suspend fun recordSaleRemote(memberId: String, ecrRefNum: String, redeemPoints: Int, staffName: String?): SaleResult? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val r = SupabaseClientProvider.client.postgrest.rpc("member_record_sale", SaleParams(memberId, ecrRefNum, redeemPoints, staffName)).decodeAs<SaleResult>()
            Log.i(TAG, "Member sale $ecrRefNum: ${r.message} +${r.earned} -${r.redeemed}")
            if (!r.success && r.message == "not_paid") null else r
        } catch (e: Exception) {
            Log.w(TAG, "Member sale failed: ${e.message}"); null
        }
    }
}
