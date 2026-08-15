package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.model.DeviceShadow
import com.goldsky.ssp.model.ShadowState
import com.pax.dal.IDAL
import com.pax.neptunelite.api.NeptuneLiteUser
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.*

/**
 * Manages the "Digital Twin" (Device Shadow) logic for IM30.
 * Synchronizes 'desired' state from cloud to hardware and 'reported' state back to cloud.
 */
object ShadowManager {
    private const val TAG = "ShadowManager"
    private var syncJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun startSync(context: Context, sn: String) {
        val client = SupabaseClientProvider.client
        
        // 1. Initial State Sync (Pull Desired)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.postgrest["device_shadows"]
                    .select { filter { eq("device_sn", sn) } }
                    .decodeSingleOrNull<DeviceShadow>()
                
                response?.desired?.let { applyDesiredState(context, it) }
                
                // Immediately report current physical state
                syncReportedState(context, sn)
            } catch (e: Exception) {
                Log.e(TAG, "Initial shadow sync failed: ${e.message}")
            }
        }

        // 2. Realtime Listener for Desired changes
        val channel = client.realtime.channel("shadow_$sn")
        val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "device_shadows"
        }

        syncJob = changeFlow.onEach { action ->
            if (action is PostgresAction.Update) {
                try {
                    val updatedShadow = action.decodeRecord<DeviceShadow>()
                    if (updatedShadow.device_sn == sn) {
                        updatedShadow.desired?.let { applyDesiredState(context, it) }
                        syncReportedState(context, sn)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Shadow decode error: ${e.message}")
                }
            }
        }.launchIn(CoroutineScope(Dispatchers.Main + Job()))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                channel.subscribe()
                Log.i(TAG, "Listening for shadow updates for SN: $sn")
            } catch (e: Exception) {
                Log.e(TAG, "Shadow subscription error: ${e.message}")
            }
        }
    }

    /**
     * Translates JSON 'desired' state into physical hardware calls.
     */
    private fun applyDesiredState(context: Context, desired: JsonObject) {
        try {
            val dal: IDAL? = try { NeptuneLiteUser.getInstance().getDal(context) } catch (e: Exception) { null }
            if (dal == null) {
                Log.w(TAG, "Skipping remote state apply: DAL not available (Simulation Mode?)")
                return
            }
            
            desired["brightness"]?.jsonPrimitive?.intOrNull?.let { value ->
                Log.i(TAG, "Applying remote brightness: $value")
                dal.getSys().setScreenBrightness(value)
            }
            
            // Add more mappings here (e.g. volume)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply desired state: ${e.message}")
        }
    }

    /**
     * Gathers current hardware info and pushes to 'reported' field in Supabase.
     */
    fun syncReportedState(context: Context, sn: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dal: IDAL? = try { NeptuneLiteUser.getInstance().getDal(context) } catch (e: Exception) { null }
                
                val currentState = ShadowState(
                    last_sync_at = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())
                )

                SupabaseClientProvider.client.postgrest["device_shadows"].update(
                    {
                        set("reported", json.encodeToJsonElement(currentState))
                    }
                ) {
                    filter { eq("device_sn", sn) }
                }
                Log.d(TAG, "Reported state synchronized to cloud")
            } catch (e: Exception) {
                Log.e(TAG, "State reporting failed: ${e.message}")
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
    }
}
