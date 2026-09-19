package com.goldsky.ssp.payment

import com.goldsky.ssp.common.CoreConfig

/**
 * Centralized configuration for Supabase integration.
 */
object SupabaseConfig {
    val URL: String get() = CoreConfig.supabaseUrl
    val KEY: String get() = CoreConfig.supabaseKey
}
