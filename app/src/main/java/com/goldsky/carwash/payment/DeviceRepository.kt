package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeviceRegistration(
    val sn: String,
    val app_version: String,
    val status: String = "ONLINE",
    val last_seen: String? = null
)

@Serializable
data class AuthResponse(
    val access_token: String,
    val expires_in: Long? = null,
    val user: UserInfo
)

@Serializable
data class UserInfo(val id: String)

@Serializable
private data class DeviceActiveState(val is_active: Boolean = true)

/**
 * Handles device identification and cloud registration with Supabase.
 */
object DeviceRepository {
    private const val TAG = "DeviceRepository"
    private const val PREFS_NAME = "device_repo_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_TOKEN_ISSUED_AT = "auth_token_issued_at"

    // Refresh proactively before the real JWT expiry (Supabase's anonymous
    // token defaults to 3600s) so a request never lands on a token that just
    // expired mid-flight.
    private const val TOKEN_TTL_MS = 50 * 60 * 1000L

    private var appContext: Context? = null
    private var authToken: String? = null
    private var tokenIssuedAt: Long = 0L

    /**
     * Must be called once (e.g. from MainActivity.onCreate) before relying on
     * a persisted token surviving process restarts.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        authToken = prefs.getString(KEY_TOKEN, null)
        tokenIssuedAt = prefs.getLong(KEY_TOKEN_ISSUED_AT, 0L)
    }

    /**
     * Returns the current JWT for isolated API access.
     */
    fun getAuthToken(): String? = authToken

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isTokenFresh(): Boolean =
        authToken != null && (System.currentTimeMillis() - tokenIssuedAt) < TOKEN_TTL_MS

    private fun persistToken(token: String) {
        authToken = token
        tokenIssuedAt = System.currentTimeMillis()
        prefs()?.edit()
            ?.putString(KEY_TOKEN, token)
            ?.putLong(KEY_TOKEN_ISSUED_AT, tokenIssuedAt)
            ?.apply()
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    /**
     * Performs Anonymous Login to Supabase and stores the JWT. No-op if the
     * currently held token is still within its TTL.
     */
    suspend fun authenticateDevice(): Boolean = withContext(Dispatchers.IO) {
        if (isTokenFresh()) return@withContext true
        try {
            val auth = retryWithBackoff(times = 3) {
                val response = client.post("${SupabaseConfig.URL}/auth/v1/signup") {
                    header("apikey", SupabaseConfig.KEY)
                    setBody("{}") // Anonymous sign-in body
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("Auth failed: ${response.status}")
                }
                response.body<AuthResponse>()
            }
            persistToken(auth.access_token)
            Log.i(TAG, "Device authenticated anonymously. UserID: ${auth.user.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Auth error after retries: ${e.message}")
            false
        }
    }

    /**
     * Registers the device or updates its online status in Supabase.
     */
    suspend fun registerDevice(sn: String, appVersion: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isTokenFresh()) authenticateDevice()

            val registration = DeviceRegistration(sn, appVersion)
            retryWithBackoff(times = 3) {
                val response = client.post("${SupabaseConfig.URL}/rest/v1/devices") {
                    header("apikey", SupabaseConfig.KEY)
                    header("Authorization", "Bearer ${authToken ?: SupabaseConfig.KEY}")
                    header("Content-Type", "application/json")
                    header("Prefer", "resolution=merge-duplicates")
                    setBody(registration)
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("Register failed: ${response.status}")
                }
            }
            Log.i(TAG, "Device $sn registered successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Registration error after retries: ${e.message}")
            false
        }
    }

    /**
     * Checks the devices.is_active remote-lock flag (the "device provisioning
     * gate" -- an admin can disable a device from the back office and it must
     * stop taking payments). Returns null on network failure; callers must
     * treat null as "unknown, leave current lock state alone", never as
     * "locked", so a transient network blip can't shut down a legitimate
     * device.
     */
    suspend fun checkDeviceActive(sn: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val states: List<DeviceActiveState> = client.get("${SupabaseConfig.URL}/rest/v1/devices") {
                header("apikey", SupabaseConfig.KEY)
                header("Authorization", "Bearer ${authToken ?: SupabaseConfig.KEY}")
                parameter("sn", "eq.$sn")
                parameter("select", "is_active")
            }.body()
            states.firstOrNull()?.is_active
        } catch (e: Exception) {
            Log.w(TAG, "Device active-state check failed: ${e.message}")
            null
        }
    }
}
