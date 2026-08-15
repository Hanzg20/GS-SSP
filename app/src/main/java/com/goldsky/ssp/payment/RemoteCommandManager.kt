package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.HardwareFactory
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class DeviceCommand(
    val id: String,
    val device_sn: String,
    val command: String,
    val payload: JsonObject? = null,
    val status: String = "PENDING"
)

/**
 * Listens for real-time commands from Supabase and executes them on the IM30.
 */
object RemoteCommandManager {
    private const val TAG = "RemoteCommandManager"
    private var listenerJob: Job? = null

    interface CommandListener {
        fun onSyncRequested()
        fun onLockRequested(locked: Boolean)
    }

    private var commandListener: CommandListener? = null

    fun startListening(context: Context, sn: String, vendor: String, listener: CommandListener) {
        commandListener = listener
        val client = SupabaseClientProvider.client
        
        // Supabase-kt 2.x: Realtime.channel(name)
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
                        executeCommand(context, command, vendor)
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

    private fun executeCommand(context: Context, deviceCommand: DeviceCommand, vendor: String) {
        val cmd = deviceCommand.command
        CoroutineScope(Dispatchers.IO).launch {
            updateCommandStatus(deviceCommand.id, "EXECUTING")
            
            var success = true
            try {
                when (cmd) {
                    "REBOOT" -> {
                        Log.w(TAG, "Executing Remote REBOOT...")
                        HardwareFactory.getHardwareProvider(vendor).reboot()
                    }
                    "SYNC_CONFIG" -> {
                        Log.i(TAG, "Executing Remote SYNC_CONFIG...")
                        withContext(Dispatchers.Main) { commandListener?.onSyncRequested() }
                    }
                    "LOCK" -> {
                        Log.w(TAG, "Executing Remote LOCK...")
                        DeviceAccessManager.setRemoteLock(true)
                        withContext(Dispatchers.Main) { commandListener?.onLockRequested(true) }
                    }
                    "UNLOCK" -> {
                        Log.i(TAG, "Executing Remote UNLOCK...")
                        DeviceAccessManager.setRemoteLock(false)
                        withContext(Dispatchers.Main) { commandListener?.onLockRequested(false) }
                    }
                    "FETCH_LOGS" -> {
                        Log.i(TAG, "Executing Remote FETCH_LOGS...")
                        success = DiagnosticManager.uploadLogs(deviceCommand.device_sn)
                    }
                    "START_SERVICE" -> {
                        val hex = deviceCommand.payload?.get("start_hex")?.jsonPrimitive?.contentOrNull
                        if (hex != null) {
                            Log.w(TAG, "Executing Remote START_SERVICE with HEX: $hex")
                            HardwareFactory.getSerialProvider(context, vendor).sendHexString(hex)
                            withContext(Dispatchers.Main) {
                                TtsManager.speak("Service started by remote administrator.")
                            }
                        } else {
                            Log.e(TAG, "START_SERVICE failed: missing start_hex payload")
                            success = false
                        }
                    }
                    else -> {
                        Log.d(TAG, "Unknown command: $cmd")
                        success = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command execution failed: ${e.message}")
                success = false
            }

            updateCommandStatus(deviceCommand.id, if (success) "SUCCESS" else "FAILED")
        }
    }

    private suspend fun updateCommandStatus(id: String, status: String) {
        try {
            SupabaseClientProvider.client.postgrest["device_commands"].update(
                {
                    set("status", status)
                }
            ) {
                filter { eq("id", id) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update command status: ${e.message}")
        }
    }

    fun stopListening() {
        listenerJob?.cancel()
    }
}
