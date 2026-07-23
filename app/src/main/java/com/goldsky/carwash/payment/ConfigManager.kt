package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import com.goldsky.carwash.model.AppConfig
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages 3-tier configuration sync: Supabase -> Local Cache -> Assets.
 */
object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val CACHE_FILE = "app_config_cache.json"

    // Used only for local cache/asset file (de)serialization -- the cloud
    // tier goes through SupabaseClientProvider.client.postgrest, the one
    // client/session shared by every repository in this app.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private var currentConfig: AppConfig? = null
    private var lastDbCheckOk = false

    /**
     * Returns true if the last database interaction was successful.
     */
    fun isDatabaseOnline(): Boolean = lastDbCheckOk

    /**
     * Gets the current configuration. Returns cached/asset version if not loaded.
     */
    fun getConfig(): AppConfig? = currentConfig

    /**
     * Actively pings Supabase to check connectivity and RLS permissions.
     */
    suspend fun checkDatabaseHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.client.postgrest["products"].select {
                limit(1)
            }
            lastDbCheckOk = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Database health check failed: ${e.message}")
            lastDbCheckOk = false
            false
        }
    }

    /**
     * Entry point to load config. Tries cloud, then cache, then assets.
     * [orgId] scopes the cloud tier to this device's tenant; pass null when
     * the device's org isn't known yet (e.g. not yet linked via
     * DeviceRepository.syncDeviceIdentity) to skip straight to cache/assets
     * rather than risk fetching an unrelated tenant's config.
     */
    suspend fun loadConfig(context: Context, orgId: String?): AppConfig = withContext(Dispatchers.IO) {
        // Tier 1: Try Cloud
        val remoteConfig = if (orgId != null) tryFetchRemoteConfig(orgId) else null
        if (remoteConfig != null) {
            saveToCache(context, remoteConfig)
            currentConfig = remoteConfig
            Log.i(TAG, "Loaded config from CLOUD (v${remoteConfig.version})")
            return@withContext remoteConfig
        }

        // Tier 2: Try Local Cache
        val cachedConfig = tryLoadCache(context)
        if (cachedConfig != null) {
            currentConfig = cachedConfig
            Log.i(TAG, "Loaded config from CACHE (v${cachedConfig.version})")
            return@withContext cachedConfig
        }

        // Tier 3: Try Assets (Safety Fallback)
        val assetConfig = loadFromAssets(context)
        currentConfig = assetConfig
        Log.i(TAG, "Loaded config from ASSETS (v${assetConfig.version})")
        assetConfig
    }

    private suspend fun tryFetchRemoteConfig(orgId: String): AppConfig? {
        return try {
            // Filtered by org_id -- previously this fetched the single most
            // recently created config row globally, so every device across
            // every tenant received whichever org's config was inserted last.
            val result = SupabaseClientProvider.client.postgrest["app_configurations"].select {
                filter { eq("org_id", orgId) }
                order("created_at", Order.DESCENDING)
                limit(1)
            }
            lastDbCheckOk = true
            result.decodeList<AppConfig>().firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Cloud fetch failed: ${e.message}")
            lastDbCheckOk = false
            null
        }
    }

    private fun tryLoadCache(context: Context): AppConfig? {
        return try {
            val file = File(context.filesDir, CACHE_FILE)
            if (file.exists()) {
                json.decodeFromString<AppConfig>(file.readText())
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Cache load failed: ${e.message}")
            null
        }
    }

    private fun saveToCache(context: Context, config: AppConfig) {
        try {
            val file = File(context.filesDir, CACHE_FILE)
            file.writeText(json.encodeToString(AppConfig.serializer(), config))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache: ${e.message}")
        }
    }

    private fun loadFromAssets(context: Context): AppConfig {
        val jsonStr = context.assets.open("default_config.json").bufferedReader().use { it.readText() }
        return json.decodeFromString<AppConfig>(jsonStr)
    }
}
