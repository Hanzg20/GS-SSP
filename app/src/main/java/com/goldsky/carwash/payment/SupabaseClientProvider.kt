package com.goldsky.carwash.payment

import android.util.Log
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

/**
 * Singleton provider for the Supabase client.
 */
object SupabaseClientProvider {
    private const val TAG = "SupabaseClientProvider"

    val client = createSupabaseClient(
        supabaseUrl = SupabaseConfig.URL,
        supabaseKey = SupabaseConfig.KEY
    ) {
        install(Postgrest)
        install(Auth)
        install(Realtime)
        install(Storage)
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
