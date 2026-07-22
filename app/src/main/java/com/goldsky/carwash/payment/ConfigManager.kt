package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import com.goldsky.carwash.model.AppConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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

    private val json = Json { 
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
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
            val token = DeviceRepository.getAuthToken()
            val response = client.get("${SupabaseConfig.URL}/rest/v1/products") {
                header("apikey", SupabaseConfig.KEY)
                header("Authorization", "Bearer ${token ?: SupabaseConfig.KEY}")
                parameter("limit", "1")
            }
            lastDbCheckOk = response.status.isSuccess()
            lastDbCheckOk
        } catch (e: Exception) {
            Log.w(TAG, "Database health check failed: ${e.message}")
            lastDbCheckOk = false
            false
        }
    }

    /**
     * Entry point to load config. Tries cloud, then cache, then assets.
     */
    suspend fun loadConfig(context: Context): AppConfig = withContext(Dispatchers.IO) {
        // Tier 1: Try Cloud
        val remoteConfig = tryFetchRemoteConfig()
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

    private suspend fun tryFetchRemoteConfig(): AppConfig? {
        return try {
            // Note: In real world, we might want to query by device SN or Merchant ID
            val token = DeviceRepository.getAuthToken()
            val response: List<AppConfig> = client.get("${SupabaseConfig.URL}/rest/v1/app_configurations") {
                header("apikey", SupabaseConfig.KEY)
                header("Authorization", "Bearer ${token ?: SupabaseConfig.KEY}")
                parameter("order", "created_at.desc")
                parameter("limit", "1")
            }.body()
            lastDbCheckOk = true
            response.firstOrNull()
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
