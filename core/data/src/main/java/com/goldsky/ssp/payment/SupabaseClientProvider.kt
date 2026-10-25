package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.SessionStatus
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
        install(Auth) {
            // Keep the session in memory across our app going to the background. With
            // the lifecycle callbacks on, Auth reloads the session from storage when the
            // app comes back to the front, and a request made in that moment went out
            // with only the publishable key: RLS matched nothing, silently. Seen twice
            // on 255 (2026-10-21): a card sale's PENDING insert as PAYWizard's screen came
            // up, and a command's final status right after the scanner page closed --
            // both are other apps' screens covering ours for seconds, the normal case
            // on these terminals. Token refresh keeps running (alwaysAutoRefresh).
            enableLifecycleCallbacks = false
        }
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

    // The SDK's retry delay (10 s) plus one refresh within our connect timeout (15 s).
    private const val SESSION_RETRY_WAIT_MS = 30_000L

    suspend fun ensureAuthenticated() {
        val signedInNow = authMutex.withLock {
            // The saved session loads asynchronously; checking before it has means
            // "no session" on every launch, and racing callers each signed in a new
            // anonymous user -- one Q2 collected 47 in a day, and a write made
            // while the session had just flipped to an unlinked one matched 0
            // rows under RLS (failed sales stuck PENDING, 2026-10-10).
            runCatching { client.auth.awaitInitialization() }
            // A saved session whose refresh couldn't reach Supabase (Wi-Fi not up yet
            // after a power cut) reads as NetworkError, and currentSessionOrNull() is
            // null then -- but the SDK still holds it and retries every 10 s. Signing in
            // here made a throwaway anonymous user each time, overwritten by that retry
            // seconds later (254: one per offline start-up, 255 collected 292).
            if (client.auth.sessionStatus.value is SessionStatus.NetworkError) {
                Log.i(TAG, "Saved session's refresh is retrying; waiting for it instead of signing in")
                withTimeoutOrNull(SESSION_RETRY_WAIT_MS) {
                    client.auth.sessionStatus.first {
                        it is SessionStatus.Authenticated || it is SessionStatus.NotAuthenticated
                    }
                }
            }
            if (client.auth.currentSessionOrNull() != null) return@withLock false
            // Still unreachable: the saved session is kept, and its retry takes over.
            if (client.auth.sessionStatus.value is SessionStatus.NetworkError) {
                Log.w(TAG, "Supabase still unreachable; keeping the saved session")
                return@withLock false
            }
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
