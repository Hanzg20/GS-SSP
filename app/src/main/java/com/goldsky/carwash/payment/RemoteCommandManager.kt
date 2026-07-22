package com.goldsky.carwash.payment

import android.content.Context
import android.util.Log
import com.pax.dal.IDAL
import com.pax.neptunelite.api.NeptuneLiteUser
import io.github.jan.supabase.realtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class DeviceCommand(
    val id: String,
    val device_sn: String,
    val command: String,
    val status: String = "PENDING"
)

/**
 * Listens for real-time commands from Supabase and executes them on the IM30.
 */
object RemoteCommandManager {
    private const val TAG = "RemoteCommandManager"
    private var listenerJob: Job? = null

    fun startListening(context: Context, sn: String, onSyncRequested: () -> Unit) {
        val client = SupabaseClientProvider.client
        
        // Supabase-kt 2.x: Realtime.channel(name), not the 3.x Realtime.createChannel(name)
        val channel = client.realtime.channel("commands_$sn")

        val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "device_commands"
        }

        listenerJob = changeFlow.onEach { action ->
            if (action is PostgresAction.Insert) {
                try {
                    val command = action.decodeRecord<DeviceCommand>()
                    if (command.device_sn == sn) {
                        Log.i(TAG, "Received remote command: ${command.command}")
                        executeCommand(context, command.command, onSyncRequested)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode command: ${e.message}")
                }
            }
        }.launchIn(CoroutineScope(Dispatchers.Main + Job()))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                channel.subscribe()
                Log.i(TAG, "Subscribed to remote commands for SN: $sn")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to subscribe: ${e.message}")
            }
        }
    }

    private fun executeCommand(context: Context, cmd: String, onSyncRequested: () -> Unit) {
        when (cmd) {
            "REBOOT" -> {
                Log.w(TAG, "Executing Remote REBOOT...")
                try {
                    val dal: IDAL = NeptuneLiteUser.getInstance().getDal(context)
                    dal.getSys().reboot()
                } catch (e: Exception) {
                    Log.e(TAG, "Reboot failed: ${e.message}")
                }
            }
            "SYNC_CONFIG" -> {
                Log.i(TAG, "Executing Remote SYNC_CONFIG...")
                onSyncRequested()
            }
            "LOCK" -> {
                Log.w(TAG, "Executing Remote LOCK...")
                DeviceAccessManager.setRemoteLock(true)
            }
            "UNLOCK" -> {
                Log.i(TAG, "Executing Remote UNLOCK...")
                DeviceAccessManager.setRemoteLock(false)
            }
            else -> Log.d(TAG, "Unknown command: $cmd")
        }
    }

    fun stopListening() {
        listenerJob?.cancel()
    }
}
