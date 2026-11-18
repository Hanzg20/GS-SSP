package com.goldsky.ssp.flex.logic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * The partner's own controller that hands the product over (Aegis Flex's whole point: the
 * terminal takes the money, the customer's machine does the rest -- e.g. Outdoor Vend's InHand
 * EC3320 over the LAN, 2026-11-05). Told after the money is in; only its "dispensed" keeps it.
 * The wire format is ours: docs/partner/flex_controller_api.md (v1) and
 * docs/partner/flex_controller_api_v1.1.md (pickup in stages, what was taken).
 */
interface FlexController {
    /**
     * One paid order to hand over. [deadline_ms]: epoch ms after which the controller must not start
     * it. [payment_flow] SALE = [amount_cents] is already charged; PREAUTH = it is only held, and
     * the controller may say how much of it to charge (DispenseOutcome.chargeCents).
     */
    @Serializable
    data class DispenseRequest(
        val order_id: String,
        val product_id: String?,
        val slot: String,
        val name: String,
        val quantity: Int,
        val amount_cents: Int,
        val currency: String,
        val payment_method: String,
        val payment_flow: String,
        val terminal_sn: String,
        val deadline_ms: Long,
        /** v1.1: what each possible taken_quantity is charged (from Cael's package prices), for the controller's records. */
        val charge_by_quantity: Map<String, Int> = emptyMap(),
        /** v1.1: the final state must be readable by then; later = given back. */
        val complete_by_ms: Long = deadline_ms,
        val api_version: String = API_VERSION,
    )

    /** v1.1 progress of a hand-over, for the screen and the voice. */
    sealed class Progress {
        object Opening : Progress()
        /** The shutter is fully open: [remainingMs] of the pickup window left (the controller's count). */
        data class Open(val remainingMs: Long) : Progress()
        /** Pickup over: [timeout] = the window ran out, else the customer finished early. */
        data class Closing(val timeout: Boolean) : Progress()
    }

    /**
     * How a hand-over went: [dispensed], and on a PREAUTH order the amount to charge of what's held
     * ([chargeCents], e.g. by weight; null = all of it). A v1.1 controller reports [takenQuantity]
     * instead (the terminal prices it); [pickupEnd] TAKEN / TIMEOUT and the weights are kept for the record.
     */
    data class DispenseOutcome(
        val dispensed: Boolean, val chargeCents: Int? = null,
        val takenQuantity: Int? = null, val pickupEnd: String? = null,
        val startWeightG: Long? = null, val endWeightG: Long? = null,
    ) {
        companion object { val FAILED = DispenseOutcome(false) }
    }

    /** Shown in the technician panel. */
    val description: String

    /** True once a controller is set up: until then a sale is given back (see [NotConnected]). */
    val connected: Boolean

    /**
     * Is it there and able to sell? True / false, or null when it can't be asked (none set up).
     * [detail] gets the controller's own words, for the technician panel; [soldOut] the slots it
     * has run out of (those tiles go grey, the rest still sell); [stock] units left per slot (v1.1).
     */
    suspend fun ready(
        detail: (String) -> Unit = {}, soldOut: (Set<String>) -> Unit = {}, stock: (Map<String, Int>) -> Unit = {},
    ): Boolean? = null

    /**
     * Hand over [request]. Dispensed = the controller confirmed it; otherwise it refused, failed or
     * never answered -- the money goes back. [onProgress]: a v1.1 controller's stages, as they come.
     */
    suspend fun dispense(request: DispenseRequest, onProgress: (Progress) -> Unit = {}): DispenseOutcome

    /**
     * No controller yet: nothing can be handed over, so every approved sale is reversed at once and
     * the screen says so -- never charged-but-unfulfilled.
     */
    object NotConnected : FlexController {
        override val description = "未设置控制器：每笔交易都会立即撤销（测试）"
        override val connected = false
        override suspend fun dispense(request: DispenseRequest, onProgress: (Progress) -> Unit) = DispenseOutcome.FAILED
    }

    /**
     * The controller on the LAN, over HTTP: GET /flex/v1/status, POST /flex/v1/dispense. A v1
     * controller answers the POST once the product is handed over (`result`); a v1.1 one accepts it
     * at once (`state`) and is then followed on GET /flex/v1/orders/{id} every [POLL_MS] until a
     * final state. [token] is sent as a Bearer token when set.
     */
    class Http(private val baseUrl: String, private val token: String?) : FlexController {
        override val description = "HTTP $baseUrl"
        override val connected = true

        @Serializable
        private data class Status(
            val ready: Boolean = false, val message: String? = null, val sold_out: List<String> = emptyList(),
            val stock: Map<String, Int> = emptyMap(),
        )

        /** v1 (`result`) or v1.1 (`state`, and the order's fields). */
        @Serializable
        private data class DispenseAnswer(
            val order_id: String? = null, val result: String? = null, val reason: String? = null, val amount_cents: Int? = null,
            val state: String? = null, val pickup_remaining_ms: Long? = null, val pickup_end: String? = null,
            val taken_quantity: Int? = null, val start_weight_g: Long? = null, val end_weight_g: Long? = null,
        )

        override suspend fun ready(
            detail: (String) -> Unit, soldOut: (Set<String>) -> Unit, stock: (Map<String, Int>) -> Unit,
        ): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = call("GET", "/flex/v1/status", null, STATUS_TIMEOUT_MS)
                if (code != 200) {
                    detail("HTTP $code")
                    return@runCatching false
                }
                val s = json.decodeFromString<Status>(body)
                detail(if (s.ready) "就绪${s.message?.let { "：$it" } ?: ""}" else "未就绪${s.message?.let { "：$it" } ?: ""}")
                soldOut(s.sold_out.toSet())
                stock(s.stock)
                s.ready
            }.getOrElse {
                detail("连不上：${it.message ?: it.javaClass.simpleName}")
                false
            }
        }

        override suspend fun dispense(request: DispenseRequest, onProgress: (Progress) -> Unit): DispenseOutcome = withContext(Dispatchers.IO) {
            // A v1 controller holds the POST until it's done (up to its own window); a v1.1 one answers at once.
            val timeout = (request.deadline_ms - System.currentTimeMillis()).coerceAtLeast(DISPENSE_WINDOW_MS) + ANSWER_GRACE_MS
            val (code, a) = runCatching {
                val (c, body) = call("POST", "/flex/v1/dispense", json.encodeToString(request), timeout)
                c to runCatching { json.decodeFromString<DispenseAnswer>(body) }.getOrNull()
            }.getOrElse {
                Log.e(TAG, "Dispense ${request.order_id}: no answer (${it.message})")
                return@withContext DispenseOutcome.FAILED
            }
            Log.i(TAG, "Dispense ${request.order_id}: HTTP $code ${a?.state ?: a?.result} ${a?.reason ?: ""}")
            if (a == null || a.order_id != request.order_id || code !in 200..299) return@withContext DispenseOutcome.FAILED
            // v1: only an explicit DISPENSED for this very order keeps the money.
            if (a.state == null) return@withContext if (a.result == "DISPENSED") DispenseOutcome(true, a.amount_cents) else DispenseOutcome.FAILED
            follow(request, a, onProgress)
        }

        /** v1.1: the order's stages until COMPLETED / FAILED, or until complete_by_ms (then given back). */
        private suspend fun follow(request: DispenseRequest, accepted: DispenseAnswer, onProgress: (Progress) -> Unit): DispenseOutcome {
            var a: DispenseAnswer? = accepted
            var last: String? = null
            while (true) {
                // null = this poll got no answer: asked again, nothing shown from a stale one.
                a?.let {
                    if (it.state != last) {
                        Log.i(TAG, "Order ${request.order_id}: ${it.state}${it.pickup_end?.let { e -> " ($e)" } ?: ""}")
                        last = it.state
                    }
                    when (it.state) {
                        "COMPLETED" -> {
                            val taken = it.taken_quantity
                            if (taken == null || taken < 0) {
                                // The final word must say what was taken; without it nothing is kept.
                                Log.e(TAG, "Order ${request.order_id}: COMPLETED without taken_quantity -- given back")
                                return DispenseOutcome.FAILED
                            }
                            return DispenseOutcome(taken > 0, takenQuantity = taken, pickupEnd = it.pickup_end,
                                startWeightG = it.start_weight_g, endWeightG = it.end_weight_g)
                        }
                        "FAILED" -> {
                            Log.w(TAG, "Order ${request.order_id}: FAILED ${it.reason ?: ""}")
                            return DispenseOutcome(false, takenQuantity = 0, pickupEnd = it.pickup_end)
                        }
                        "OPENING" -> onProgress(Progress.Opening)
                        "OPEN" -> it.pickup_remaining_ms?.let { ms -> onProgress(Progress.Open(ms.coerceAtLeast(0))) }
                        "CLOSING" -> onProgress(Progress.Closing(it.pickup_end == "TIMEOUT"))
                    }
                }
                if (System.currentTimeMillis() > request.complete_by_ms) {
                    Log.e(TAG, "Order ${request.order_id}: no final state by complete_by_ms (last $last) -- given back")
                    return DispenseOutcome.FAILED
                }
                kotlinx.coroutines.delay(POLL_MS)
                a = runCatching {
                    val (c, body) = call("GET", "/flex/v1/orders/" + java.net.URLEncoder.encode(request.order_id, "UTF-8"), null, POLL_TIMEOUT_MS)
                    if (c == 200) json.decodeFromString<DispenseAnswer>(body).takeIf { it.order_id == request.order_id } else null
                }.onFailure { Log.w(TAG, "Order ${request.order_id}: ${it.message}") }.getOrNull()
            }
        }

        private fun call(method: String, path: String, body: String?, timeoutMs: Long): Pair<Int, String> {
            val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = timeoutMs.toInt()
                conn.setRequestProperty("Accept", "application/json")
                token?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                return code to text
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Cael's shared Test simulator stands in for the machine (owner 2026-11-05): the request goes to
     * the cloud and a person answers DISPENSED / FAILED on the page; READY / NOT READY is set there
     * too. For tests anywhere -- no PC on the terminal's LAN needed. Payments stay real.
     */
    object CaelSim : FlexController {
        override val description = "Cael 测试模拟器（在 Cael 的“测试模拟器”页面操作）"
        override val connected = true

        override suspend fun ready(detail: (String) -> Unit, soldOut: (Set<String>) -> Unit, stock: (Map<String, Int>) -> Unit): Boolean {
            val s = com.goldsky.ssp.payment.TestSimRepository.status()
            if (s == null || !s.success) {
                detail("连不上 Cael${s?.message?.let { "：$it" } ?: ""}")
                return false
            }
            detail(if (s.ready) "就绪（Cael 测试模拟器）" else "未就绪${s.message?.let { "：$it" } ?: "（Cael 测试模拟器）"}")
            soldOut(s.sold_out.toSet())
            return s.ready
        }

        override suspend fun dispense(request: DispenseRequest, onProgress: (Progress) -> Unit): DispenseOutcome {
            val payload = json.encodeToJsonElement(DispenseRequest.serializer(), request) as kotlinx.serialization.json.JsonObject
            if (!com.goldsky.ssp.payment.TestSimRepository.submit(KIND, request.order_id, payload, request.complete_by_ms)) {
                Log.e(TAG, "Simulator: ${request.order_id} not delivered")
                return DispenseOutcome.FAILED
            }
            // A person answers on the page; asked every second until the deadline (+ grace).
            val until = request.complete_by_ms + ANSWER_GRACE_MS
            while (System.currentTimeMillis() < until) {
                val a = com.goldsky.ssp.payment.TestSimRepository.answerOf(KIND, request.order_id)
                if (a?.answered == true) {
                    val result = (a.answer?.get("result") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val charge = (a.answer?.get("amount_cents") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    // The page may say how many were taken (v1.1); the terminal prices that.
                    val taken = (a.answer?.get("taken_quantity") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    Log.i(TAG, "Simulator: ${request.order_id} -> $result${charge?.let { " (charge $it)" } ?: ""}${taken?.let { " (taken $it)" } ?: ""}")
                    return if (result == "DISPENSED") DispenseOutcome(taken?.let { it > 0 } ?: true, charge, takenQuantity = taken)
                    else DispenseOutcome.FAILED
                }
                kotlinx.coroutines.delay(1_000)
            }
            Log.e(TAG, "Simulator: ${request.order_id} unanswered")
            return DispenseOutcome.FAILED
        }

        private const val KIND = "FLEX_DISPENSE"
    }

    companion object {
        private const val TAG = "FlexController"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val STATUS_TIMEOUT_MS = 3_000L
        /** After the deadline, how long the controller still has to say how it went. */
        private const val ANSWER_GRACE_MS = 10_000L
        /** v1: how long a hand-over may take (the POST is held that long). */
        const val DISPENSE_WINDOW_MS = 90_000L
        /** v1.1: the controller must not open the shutter after now + this (deadline_ms). */
        const val START_WINDOW_MS = 30_000L
        /** v1.1: the final state must be readable within this (shutter, pickup window, closing, weight settling). */
        const val COMPLETE_WINDOW_MS = 300_000L
        const val API_VERSION = "1.1"
        private const val POLL_MS = 500L
        private const val POLL_TIMEOUT_MS = 2_000L

        private fun prefs(context: Context) = context.getSharedPreferences("flex_controller", Context.MODE_PRIVATE)

        /** The address that means [CaelSim] instead of a machine on the LAN. */
        const val CAEL_SIM = "cael"

        /** This terminal's controller, set in the technician panel (it's per site, not per store). */
        fun load(context: Context): FlexController {
            val url = prefs(context).getString("url", null)?.takeIf { it.isNotBlank() } ?: return NotConnected
            if (url == CAEL_SIM) return CaelSim
            return Http(url, prefs(context).getString("token", null))
        }

        fun url(context: Context): String = prefs(context).getString("url", null).orEmpty()

        /**
         * How the card is taken: SALE (charged at once, given back if not dispensed) or PREAUTH
         * (held at the price, then charged for what the controller says was handed over -- e.g. by
         * weight -- or released). Per terminal, like the controller.
         */
        fun preauth(context: Context): Boolean = prefs(context).getString("flow", null) == "PREAUTH"
        fun savePreauth(context: Context, on: Boolean) =
            prefs(context).edit().putString("flow", if (on) "PREAUTH" else "SALE").apply()
        fun token(context: Context): String = prefs(context).getString("token", null).orEmpty()

        /** Blank [url] = none (back to [NotConnected]). "192.168.1.50:8080" gets http:// in front. */
        fun save(context: Context, url: String, token: String) {
            val u = url.trim().let { if (it.isBlank() || it == CAEL_SIM || it.contains("://")) it else "http://$it" }
            prefs(context).edit().putString("url", u).putString("token", token.trim()).apply()
        }
    }
}
