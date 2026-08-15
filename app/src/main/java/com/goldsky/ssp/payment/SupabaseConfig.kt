package com.goldsky.ssp.payment

import com.goldsky.ssp.BuildConfig

/**
 * Centralized configuration for Supabase integration.
 * Values are injected during build from local.properties via Gradle.
 */
object SupabaseConfig {
    val URL: String = BuildConfig.SUPABASE_URL
    val KEY: String = BuildConfig.SUPABASE_KEY
}
