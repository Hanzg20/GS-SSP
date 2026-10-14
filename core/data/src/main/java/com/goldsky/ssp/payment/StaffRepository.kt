package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A staff row as the terminal sees it (docs/migrations/2026-10-10_staff.sql). */
@Serializable
data class StaffMember(
    val id: String,
    val name: String,
    val role: String,
    val pin_salt: String,
    val pin_hash: String,
    val pin_iterations: Int
) {
    val isManager: Boolean get() = role == "MANAGER"
    val isDriver: Boolean get() = role == "DRIVER"
}

/**
 * The org's staff, for PIN sign-in on Iris/Ourea. The list is cached so
 * sign-in works offline; [sync] replaces it whenever the cloud answers
 * (a disabled or removed person stops working at the next sync).
 * [current] is who is signed in on this terminal right now.
 */
object StaffRepository {
    private const val TAG = "StaffRepository"
    private const val PREFS = "staff"
    private const val KEY_LIST = "list"
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(StaffMember.serializer())

    private var appContext: Context? = null

    @Volatile var current: StaffMember? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** null = never synced on this terminal (vs. an empty list = the org has no staff). */
    fun cached(): List<StaffMember>? =
        prefs()?.getString(KEY_LIST, null)?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }

    /** true when the cloud answered (the cache now matches it). */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        try {
            val list = SupabaseClientProvider.client.postgrest["staff"]
                .select(Columns.raw("id, name, role, pin_salt, pin_hash, pin_iterations")) {
                    filter { eq("is_active", true) }
                }
                .decodeList<StaffMember>()
            prefs()?.edit()?.putString(KEY_LIST, json.encodeToString(listSerializer, list))?.apply()
            // Someone signed in who was just disabled stays signed in until the next lock.
            Log.i(TAG, "Staff synced: ${list.size}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Staff sync failed, keeping the cached list: ${e.message}")
            false
        }
    }

    sealed class PinResult {
        data class Match(val staff: StaffMember) : PinResult()
        object NoMatch : PinResult()
        // CMP keeps PINs unique, but can't check when someone is re-enabled
        // (salted hashes) -- so two people can end up sharing one. Refuse
        // rather than sign in as whichever comes first.
        object Ambiguous : PinResult()
    }

    fun verify(pin: String): PinResult {
        val hits = cached().orEmpty().filter { PinHasher.matches(pin, it.pin_salt, it.pin_hash, it.pin_iterations) }
        return when (hits.size) {
            0 -> PinResult.NoMatch
            1 -> PinResult.Match(hits[0])
            else -> PinResult.Ambiguous
        }
    }

    fun signIn(staff: StaffMember) {
        current = staff
        Log.i(TAG, "Signed in: ${staff.name} (${staff.role})")
    }

    /** The device moved to another store: its staff list and sign-in no longer apply. */
    fun forgetStore() {
        prefs()?.edit()?.remove(KEY_LIST)?.apply()
        current = null
    }

    fun signOut() {
        current?.let { Log.i(TAG, "Signed out: ${it.name}") }
        current = null
    }
}
