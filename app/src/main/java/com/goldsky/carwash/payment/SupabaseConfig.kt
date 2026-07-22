package com.goldsky.carwash.payment

import com.goldsky.carwash.BuildConfig

/**
 * Centralized configuration for Supabase integration.
 * Values are injected during build from local.properties via Gradle.
 */
object SupabaseConfig {
    val URL: String = BuildConfig.SUPABASE_URL
    val KEY: String = BuildConfig.SUPABASE_KEY
}
