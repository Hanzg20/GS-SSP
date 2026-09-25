package com.goldsky.ssp.payment

import android.content.Context
import android.util.Log
import com.goldsky.ssp.common.TtsManager
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

        /**
         * Remote START_SERVICE from CMP: run [productId]'s service once, free,
         * through the app's own output path (wash: DispenseEngine, so the
         * Q3mini's DigitIo pulse circuit; timer: a hold session). Returns
         * whether it started, or null if this app doesn't handle it -- only
         * then does the legacy raw-serial [startHex] write run, which on a
         * Q3mini drives a port nothing is wired to.
         */
        suspend fun onStartServiceRequested(productId: String?, startHex: String?, commandId: String): Boolean? = null
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
            catchUpLockState(sn)
        }
    }

    /**
     * Commands only arrive as live Realtime inserts, so one sent while the
     * app wasn't running (restart, reinstall, reboot) is never received and
     * sits PENDING forever -- seen live 2026-09-24: three LOCKs sent during
     * an app reinstall stayed PENDING. At startup, re-apply the most recent
     * LOCK/UNLOCK whatever its status -- it is the operator's latest intent
     * and applying it twice is harmless -- then report the resulting state.
     * (Only applying PENDING ones wasn't enough, also seen live: a LOCK
     * already marked SUCCESS by another process on the same SN left this
     * one unlocked, and it then reported remote_locked=false over the
     * truth.)
     * Deliberately ONLY LOCK/UNLOCK (idempotent state): replaying a stale
     * START_SERVICE would dispense a free service, and a stale REBOOT would
     * reboot a terminal nobody meant to reboot now.
     */
    private suspend fun catchUpLockState(sn: String) {
        try {
            val latest = SupabaseClientProvider.client.postgrest["device_commands"].select {
                filter {
                    eq("device_sn", sn)
                    isIn("command", listOf("LOCK", "UNLOCK"))
                }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                limit(1)
            }.decodeList<DeviceCommand>().firstOrNull()
            if (latest != null) {
                val lock = latest.command == "LOCK"
                if (lock != DeviceAccessManager.isRemoteLocked()) {
                    Log.w(TAG, "Applying latest ${latest.command} (${latest.id}, ${latest.status}) at startup")
                    DeviceAccessManager.setRemoteLock(lock)
                    withContext(Dispatchers.Main) { commandListener?.onLockRequested(lock) }
                }
                if (latest.status == "PENDING") updateCommandStatus(latest.id, "SUCCESS")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lock catch-up failed: ${e.message}")
        }
        DeviceRepository.reportRemoteLock(sn, DeviceAccessManager.isRemoteLocked())
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
                        // Recorded before the call: a reboot that works kills
                        // this process before the status write at the end.
                        // A refused one overwrites it with FAILED below.
                        updateCommandStatus(deviceCommand.id, "SUCCESS")
                        delay(500)
                        success = HardwareFactory.getHardwareProvider(vendor).reboot()
                    }
                    "SYNC_CONFIG" -> {
                        Log.i(TAG, "Executing Remote SYNC_CONFIG...")
                        withContext(Dispatchers.Main) { commandListener?.onSyncRequested() }
                    }
                    "LOCK" -> {
                        Log.w(TAG, "Executing Remote LOCK...")
                        DeviceAccessManager.setRemoteLock(true)
                        withContext(Dispatchers.Main) { commandListener?.onLockRequested(true) }
                        DeviceRepository.reportRemoteLock(deviceCommand.device_sn, true)
                    }
                    "UNLOCK" -> {
                        Log.i(TAG, "Executing Remote UNLOCK...")
                        DeviceAccessManager.setRemoteLock(false)
                        withContext(Dispatchers.Main) { commandListener?.onLockRequested(false) }
                        DeviceRepository.reportRemoteLock(deviceCommand.device_sn, false)
                    }
                    "FETCH_LOGS" -> {
                        Log.i(TAG, "Executing Remote FETCH_LOGS...")
                        // uploadLogs() now returns a 3-way result (see its doc
                        // comment) instead of a Boolean -- Empty is treated as
                        // a failure here (device_commands.status has no room
                        // for a third value without a schema change), but
                        // reported distinctly via reportError so whoever
                        // issued this command from CMP can tell "nothing
                        // useful captured" apart from a generic failure. This
                        // matters MORE for a remote-triggered fetch than for
                        // the tech dashboard's own button: there's by
                        // definition nobody physically at the kiosk to
                        // approve the Android 12+ log-access consent dialog
                        // uploadLogs's doc comment describes, so Empty is the
                        // likely/expected outcome here, not an edge case.
                        when (val result = DiagnosticManager.uploadLogs(deviceCommand.device_sn)) {
                            is LogUploadResult.Success -> success = true
                            is LogUploadResult.Empty -> {
                                success = false
                                DiagnosticManager.reportError(
                                    deviceCommand.device_sn, "FETCH_LOGS_EMPTY", severity = "WARNING",
                                    trace = "Captured only ${result.lineCount} lines -- likely blocked by the Android 12+ log-access consent dialog, which needs a human physically at the device to approve"
                                )
                            }
                            is LogUploadResult.Failed -> success = false
                        }
                    }
                    "START_SERVICE" -> {
                        val hex = deviceCommand.payload?.get("start_hex")?.jsonPrimitive?.contentOrNull
                        val productId = deviceCommand.payload?.get("product_id")?.jsonPrimitive?.contentOrNull
                        val handled = withContext(Dispatchers.Main) {
                            commandListener?.onStartServiceRequested(productId, hex, deviceCommand.id)
                        }
                        if (handled != null) {
                            Log.w(TAG, "Remote START_SERVICE product=$productId -> ${if (handled) "started" else "refused"}")
                            success = handled
                        } else if (hex != null) {
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
