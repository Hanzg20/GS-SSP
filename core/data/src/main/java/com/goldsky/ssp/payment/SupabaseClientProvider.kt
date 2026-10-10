package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.ktor.client.*
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

/**
 * Singleton provider for the Supabase client.
 */
object SupabaseClientProvider {
    private const val TAG = "SupabaseClientProvider"

    // Industrial Connection Pool: limit concurrent connections to conserve 
    // memory on 2GB RAM terminals.
    private val industrialPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    val client = createSupabaseClient(
        supabaseUrl = SupabaseConfig.URL,
        supabaseKey = SupabaseConfig.KEY
    ) {
        httpEngine = OkHttp.create {
            config {
                connectionPool(industrialPool)
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
            }
        }
        install(Postgrest)
        install(Auth)
        install(Realtime)
        install(Storage)
    }

    /**
     * Drops every pooled connection. Call when the network changes: a pooled
     * socket from the old network is dead, and a request written to it sits
     * out the full read timeout (Wi-Fi off/on on the Q2: the first heartbeat
     * after reconnecting hung ~30 s).
     */
    fun dropConnections() = industrialPool.evictAll()

    private val functionsHttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectionPool(industrialPool)
            }
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /**
     * Invokes a Supabase Edge Function under supabase/functions/<name> and
     * returns the raw response. Callers decode the body themselves since
     * each function's response shape differs.
     */
    suspend fun invokeFunction(name: String, body: Any): HttpResponse =
        functionsHttpClient.post("${SupabaseConfig.URL}/functions/v1/$name") {
            header("apikey", SupabaseConfig.KEY)
            header("Authorization", "Bearer ${client.auth.currentAccessTokenOrNull() ?: SupabaseConfig.KEY}")
            header("Content-Type", "application/json")
            setBody(body)
        }

    /**
     * The Auth plugin above was previously installed but never actually
     * signed in, so every postgrest/realtime call through [client] (used by
     * TransactionRepository, ShadowManager, QrPaymentRepository,
     * VipRepository, RemoteCommandManager) ran as the `anon` role rather
     * than `authenticated` -- silently defeating any RLS policy scoped to
     * `TO authenticated`. Must be called (and awaited) once before those
     * repositories are used; Supabase-kt persists the resulting session, so
     * this is a no-op on subsequent app launches.
     */
    // One sign-in at a time: several start-up jobs (catalog, staff, replay,
    // heartbeat) call this at once.
    private val authMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureAuthenticated() {
        val signedInNow = authMutex.withLock {
            // The saved session loads asynchronously; checking before it has means
            // "no session" on every launch, and racing callers each signed in a new
            // anonymous user -- one Q2 collected 47 in a day, and a write made
            // while the session had just flipped to an unlinked one matched 0
            // rows under RLS (failed sales stuck PENDING, 2026-10-10).
            runCatching { client.auth.awaitInitialization() }
            if (client.auth.currentSessionOrNull() != null) return@withLock false
            try {
                client.auth.signInAnonymously()
                Log.i(TAG, "Signed in anonymously for postgrest/realtime access")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Anonymous sign-in failed: ${e.message}")
                false
            }
        }
        // A fresh anonymous sign-in is a NEW auth user that device_auth_map
        // has never heard of: every RLS policy scoped through it (transactions,
        // heartbeats, shadows) then silently matches zero rows -- seen live
        // 2026-09-20, card payments approved by the terminal but left PENDING
        // in the database. Re-link it right away instead of waiting for the
        // next app launch. Outside the lock: the re-link calls back into
        // ensureAuthenticated(), and the Mutex isn't re-entrant -- holding it
        // here deadlocked every fresh install (Ourea emulator, 2026-10-10).
        if (signedInNow) relinkDeviceIdentity()
    }

    /** Re-binds the current auth session to this device (device_auth_map). No-op before first registration. */
    suspend fun relinkDeviceIdentity() {
        val sn = DeviceRepository.getPersistedDeviceSn() ?: return
        try {
            DeviceRepository.syncDeviceIdentity(sn)
            Log.i(TAG, "Re-linked auth session to device $sn")
        } catch (e: Exception) {
            Log.e(TAG, "Re-link failed: ${e.message}")
        }
    }
}
