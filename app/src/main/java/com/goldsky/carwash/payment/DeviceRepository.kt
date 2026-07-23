package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class DeviceRegistration(
    val sn: String,
    val app_version: String,
    val status: String = "ONLINE",
    val last_seen: String? = null
)

@Serializable
data class SyncDeviceIdentityResult(
    val success: Boolean,
    val message: String? = null,
    val org_id: String? = null,
    val is_active: Boolean? = null
)

/**
 * Handles device identification and cloud registration with Supabase.
 */
object DeviceRepository {
    private const val TAG = "DeviceRepository"
    private const val PREFS_NAME = "device_repo_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_DEVICE_SN = "device_sn"
    private const val KEY_ORG_ID = "org_id"

    private var appContext: Context? = null
    private var cachedToken: String? = null

    /**
     * Must be called once (e.g. from MainActivity.onCreate) before relying on
     * a persisted token surviving process restarts.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        cachedToken = prefs()?.getString(KEY_TOKEN, null)
    }

    /**
     * Always prefers the live token from SupabaseClientProvider's session
     * (auto-refreshed by the supabase-kt SDK in the background) over the
     * SharedPreferences snapshot below -- a long-running kiosk process must
     * never keep using a token that expired hours ago just because it was
     * cached once at startup. The persisted value is only a fallback for the
     * narrow window before any sign-in has happened yet in this process.
     */
    fun getAuthToken(): String? =
        SupabaseClientProvider.client.auth.currentAccessTokenOrNull() ?: cachedToken

    /**
     * Persists the hardware/simulated SN resolved in MainActivity so
     * background components without their own DAL access (e.g.
     * HeartbeatWorker, which runs as an independent WorkManager job) can
     * still identify the device instead of falling back to a placeholder.
     */
    fun persistDeviceSn(sn: String) {
        prefs()?.edit()?.putString(KEY_DEVICE_SN, sn)?.apply()
    }

    fun getPersistedDeviceSn(): String? = prefs()?.getString(KEY_DEVICE_SN, null)

    private fun persistOrgId(orgId: String?) {
        if (orgId == null) return
        prefs()?.edit()?.putString(KEY_ORG_ID, orgId)?.apply()
    }

    /**
     * The tenant this device belongs to, resolved via [syncDeviceIdentity].
     * Null until that call has succeeded at least once (e.g. a brand new
     * device not yet assigned to a location by an admin).
     */
    fun getPersistedOrgId(): String? = prefs()?.getString(KEY_ORG_ID, null)

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Ensures the app's ONE shared anonymous session (owned by
     * SupabaseClientProvider) is established, then mirrors its access token
     * into SharedPreferences as a cold-start fallback for [getAuthToken].
     *
     * Previously this did its own independent POST /auth/v1/signup, which
     * created a SECOND, different anonymous user from the one
     * SupabaseClientProvider signs in. sync_device_identity() -- called with
     * THIS class's token -- populated device_auth_map for that first UID,
     * but TransactionRepository/VipRepository/ShadowManager/etc all
     * authenticate via SupabaseClientProvider.client and so ran as the
     * SECOND UID, which device_auth_map never knew about. Every RLS policy
     * scoped through device_auth_map (transactions, heartbeats, shadows...)
     * silently denied those repositories even after a "successful" identity
     * sync -- confirmed live: transactions INSERT kept getting rejected with
     * "new row violates row-level security policy" while sync_device_identity
     * itself reported success, because the two were never the same identity.
     */
    suspend fun authenticateDevice(): Boolean = withContext(Dispatchers.IO) {
        SupabaseClientProvider.ensureAuthenticated()
        val token = getAuthToken()
        if (token == null) {
            Log.e(TAG, "Auth error: no active Supabase session")
            return@withContext false
        }
        cachedToken = token
        prefs()?.edit()?.putString(KEY_TOKEN, token)?.apply()
        Log.i(TAG, "Device authenticated anonymously. UserID: ${SupabaseClientProvider.client.auth.currentSessionOrNull()?.user?.id}")
        true
    }

    /**
     * Registers the device or updates its online status in Supabase.
     */
    suspend fun registerDevice(sn: String, appVersion: String): Boolean = withContext(Dispatchers.IO) {
        try {
            authenticateDevice()

            val registration = DeviceRegistration(sn, appVersion)
            retryWithBackoff(times = 3) {
                SupabaseClientProvider.client.postgrest["devices"].upsert(registration, onConflict = "sn")
            }
            Log.i(TAG, "Device $sn registered successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Registration error after retries: ${e.message}")
            false
        }
    }

    /**
     * Links this device's auth session to its device_auth_map row (via the
     * sync_device_identity RPC, SECURITY DEFINER) -- required for every
     * device-scoped RLS policy in the schema to pass -- and returns the
     * device's current org_id/is_active in the same round trip. Replaces the
     * old checkDeviceActive() raw SELECT, which needed its own RLS policy on
     * devices that never existed.
     *
     * is_active null (network failure, or RPC-level failure e.g. device not
     * yet provisioned) must be treated by callers as "unknown, leave current
     * lock state alone", never as "locked", so a transient blip or an
     * unprovisioned device can't false-positive lock a legitimate device.
     */
    suspend fun syncDeviceIdentity(sn: String): SyncDeviceIdentityResult? = withContext(Dispatchers.IO) {
        try {
            authenticateDevice()
            val response = SupabaseClientProvider.client.postgrest.rpc("sync_device_identity", mapOf("p_sn" to sn))
            val result: SyncDeviceIdentityResult = response.decodeAs()
            if (!result.success) {
                Log.w(TAG, "sync_device_identity rejected: ${result.message}")
                return@withContext null
            }
            persistOrgId(result.org_id)
            result
        } catch (e: Exception) {
            Log.w(TAG, "Device identity sync failed: ${e.message}")
            null
        }
    }
}
