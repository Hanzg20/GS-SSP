package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Aegis Vend's terminal and the big screen beside the machine (Aegis Vend Screen, an Android
 * display), linked through Supabase Realtime Broadcast: nothing is written to the database.
 * Through the cloud rather than the shop's network: the terminal is often on 4G and the
 * screen on the site's Wi-Fi.
 *
 *  - state: the terminal sends what its screen shows on `vend_screen_<terminal SN>` on every
 *    change and again every [RESEND_MS], so a screen that (re)joins catches up and can tell
 *    the terminal is gone;
 *  - pairing: the screen shows a 6-digit code and waits on `vend_pair_<code>`; the technician
 *    types it on the terminal, which sends its SN there; the screen answers with its own.
 *
 * Display only: the screen never takes a payment, so a message here can't move money.
 */
object VendScreenLink {
    private const val TAG = "VendScreenLink"
    const val RESEND_MS = 15_000L
    private const val EVENT_STATE = "state"
    private const val EVENT_PAIR = "pair"
    private const val EVENT_PAIRED = "paired"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun stateTopic(terminalSn: String) = "vend_screen_" + terminalSn.lowercase().filter { it.isLetterOrDigit() }
    private fun pairTopic(code: String) = "vend_pair_$code"

    /** Joins [topic], retrying until it works (offline at boot, network back later). */
    private suspend fun join(topic: String): RealtimeChannel {
        while (true) {
            try {
                SupabaseClientProvider.ensureAuthenticated()
                val client = SupabaseClientProvider.client
                runCatching { client.realtime.connect() }
                val ch = client.channel(topic) { broadcast { receiveOwnBroadcasts = false } }
                ch.subscribe(blockUntilSubscribed = true)
                return ch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Join $topic failed: ${e.message}; retrying")
                delay(10_000)
            }
        }
    }

    private suspend fun leave(ch: RealtimeChannel?) {
        ch ?: return
        runCatching { SupabaseClientProvider.client.realtime.removeChannel(ch) }
    }

    // ---- terminal: publish -----------------------------------------------------

    private var publishJob: Job? = null
    @Volatile private var channel: RealtimeChannel? = null
    @Volatile private var current: (() -> JsonObject)? = null

    /**
     * Starts sending for this terminal (idempotent). [current] encodes what the screen shows
     * at the time of each send -- so a countdown repeated 15 s later says 15 s less.
     */
    fun startPublishing(terminalSn: String, current: () -> JsonObject) {
        this.current = current
        if (terminalSn.isBlank() || publishJob?.isActive == true) return
        publishJob = scope.launch {
            channel = join(stateTopic(terminalSn))
            Log.i(TAG, "Publishing on ${stateTopic(terminalSn)}")
            while (isActive) {
                publish()
                delay(RESEND_MS)
            }
        }
    }

    /** This terminal no longer has a big screen (technician panel: standalone). */
    fun stopPublishing() {
        publishJob?.cancel()
        publishJob = null
        val ch = channel
        channel = null
        current = null
        scope.launch { leave(ch) }
    }

    /** The screen changed: send it now (and it keeps being repeated). */
    fun publish() {
        val message = runCatching { current?.invoke() }.getOrNull() ?: return
        scope.launch { send(message) }
    }

    private suspend fun send(message: JsonObject) {
        val ch = channel ?: return
        runCatching { ch.broadcast(EVENT_STATE, message) }.onFailure { Log.w(TAG, "Send failed: ${it.message}") }
    }

    /**
     * Pairs the screen showing [code] with this terminal; the screen's SN, or null when no
     * screen answered within [timeoutMs].
     */
    suspend fun pair(code: String, terminalSn: String, timeoutMs: Long = 20_000): String? {
        var ch: RealtimeChannel? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                ch = join(pairTopic(code))
                val answer = async { ch!!.broadcastFlow<JsonObject>(EVENT_PAIRED).first() }
                val offer = buildJsonObject { put("terminal_sn", terminalSn) }
                // Sent until answered: the screen may join a moment after us.
                while (!answer.isCompleted) {
                    runCatching { ch!!.broadcast(EVENT_PAIR, offer) }
                    delay(2_000)
                }
                answer.await()["screen_sn"]?.jsonPrimitive?.content ?: ""
            }
        } finally {
            withContext(NonCancellable) { leave(ch) }
        }
    }

    // ---- big screen: listen ------------------------------------------------------

    /**
     * Waits on [code] for a terminal and answers with [screenSn]; returns the terminal's SN.
     * Cancel to stop waiting.
     */
    suspend fun awaitPairing(code: String, screenSn: String): String {
        var ch: RealtimeChannel? = null
        try {
            ch = join(pairTopic(code))
            val offer = ch.broadcastFlow<JsonObject>(EVENT_PAIR).first()
            val terminal = offer["terminal_sn"]?.jsonPrimitive?.content.orEmpty()
            // Answered a few times: the terminal stops sending once it has one.
            repeat(3) {
                runCatching { ch.broadcast(EVENT_PAIRED, buildJsonObject { put("screen_sn", screenSn) }) }
                delay(500)
            }
            return terminal
        } finally {
            withContext(NonCancellable) { leave(ch) }
        }
    }

    /** Follows a terminal: [onMessage] on the main thread with each state it sends. */
    fun listen(terminalSn: String, scope: CoroutineScope, onMessage: (JsonObject) -> Unit): Job = scope.launch {
        var ch: RealtimeChannel? = null
        try {
            ch = withContext(Dispatchers.IO) { join(stateTopic(terminalSn)) }
            Log.i(TAG, "Listening on ${stateTopic(terminalSn)}")
            ch.broadcastFlow<JsonObject>(EVENT_STATE).onEach { onMessage(it) }.launchIn(this)
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { leave(ch) }
        }
    }
}
