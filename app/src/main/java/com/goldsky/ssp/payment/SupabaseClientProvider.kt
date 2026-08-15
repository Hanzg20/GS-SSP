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
    suspend fun ensureAuthenticated() {
        if (client.auth.currentSessionOrNull() != null) return
        try {
            client.auth.signInAnonymously()
            Log.i(TAG, "Signed in anonymously for postgrest/realtime access")
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous sign-in failed: ${e.message}")
        }
    }
}
