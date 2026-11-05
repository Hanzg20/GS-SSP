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
 * The wire format is ours: docs/flex_controller_api.md.
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
    )

    /**
     * How a hand-over went: [dispensed], and on a PREAUTH order the amount to charge of what's held
     * ([chargeCents], e.g. by weight; null = all of it).
     */
    data class DispenseOutcome(val dispensed: Boolean, val chargeCents: Int? = null) {
        companion object { val FAILED = DispenseOutcome(false) }
    }

    /** Shown in the technician panel. */
    val description: String

    /** True once a controller is set up: until then a sale is given back (see [NotConnected]). */
    val connected: Boolean

    /**
     * Is it there and able to sell? True / false, or null when it can't be asked (none set up).
     * [detail] gets the controller's own words, for the technician panel.
     */
    suspend fun ready(detail: (String) -> Unit = {}): Boolean? = null

    /**
     * Hand over [request]. Dispensed = the controller confirmed it; otherwise it refused, failed or
     * never answered -- the money goes back.
     */
    suspend fun dispense(request: DispenseRequest): DispenseOutcome

    /**
     * No controller yet: nothing can be handed over, so every approved sale is reversed at once and
     * the screen says so -- never charged-but-unfulfilled.
     */
    object NotConnected : FlexController {
        override val description = "未设置控制器：每笔交易都会立即撤销（测试）"
        override val connected = false
        override suspend fun dispense(request: DispenseRequest) = DispenseOutcome.FAILED
    }

    /**
     * The controller on the LAN, over HTTP (docs/flex_controller_api.md): GET /flex/v1/status,
     * POST /flex/v1/dispense, answered once the product is handed over (or not). [token] is
     * sent as a Bearer token when set.
     */
    class Http(private val baseUrl: String, private val token: String?) : FlexController {
        override val description = "HTTP $baseUrl"
        override val connected = true

        @Serializable
        private data class Status(val ready: Boolean = false, val message: String? = null)

        @Serializable
        private data class DispenseAnswer(
            val order_id: String? = null, val result: String? = null, val reason: String? = null, val amount_cents: Int? = null,
        )

        override suspend fun ready(detail: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = call("GET", "/flex/v1/status", null, STATUS_TIMEOUT_MS)
                if (code != 200) {
                    detail("HTTP $code")
                    return@runCatching false
                }
                val s = json.decodeFromString<Status>(body)
                detail(if (s.ready) "就绪${s.message?.let { "：$it" } ?: ""}" else "未就绪${s.message?.let { "：$it" } ?: ""}")
                s.ready
            }.getOrElse {
                detail("连不上：${it.message ?: it.javaClass.simpleName}")
                false
            }
        }

        override suspend fun dispense(request: DispenseRequest): DispenseOutcome = withContext(Dispatchers.IO) {
            val timeout = (request.deadline_ms - System.currentTimeMillis()).coerceAtLeast(5_000L) + ANSWER_GRACE_MS
            runCatching {
                val (code, body) = call("POST", "/flex/v1/dispense", json.encodeToString(request), timeout)
                val a = runCatching { json.decodeFromString<DispenseAnswer>(body) }.getOrNull()
                Log.i(TAG, "Dispense ${request.order_id}: HTTP $code ${a?.result} ${a?.reason ?: ""}")
                // Only an explicit DISPENSED for this very order keeps the money.
                if (code == 200 && a?.result == "DISPENSED" && a.order_id == request.order_id) DispenseOutcome(true, a.amount_cents)
                else DispenseOutcome.FAILED
            }.getOrElse {
                Log.e(TAG, "Dispense ${request.order_id}: no answer (${it.message})")
                DispenseOutcome.FAILED
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

        override suspend fun ready(detail: (String) -> Unit): Boolean {
            val s = com.goldsky.ssp.payment.TestSimRepository.status()
            if (s == null || !s.success) {
                detail("连不上 Cael${s?.message?.let { "：$it" } ?: ""}")
                return false
            }
            detail(if (s.ready) "就绪（Cael 测试模拟器）" else "未就绪${s.message?.let { "：$it" } ?: "（Cael 测试模拟器）"}")
            return s.ready
        }

        override suspend fun dispense(request: DispenseRequest): DispenseOutcome {
            val payload = json.encodeToJsonElement(DispenseRequest.serializer(), request) as kotlinx.serialization.json.JsonObject
            if (!com.goldsky.ssp.payment.TestSimRepository.submit(KIND, request.order_id, payload, request.deadline_ms)) {
                Log.e(TAG, "Simulator: ${request.order_id} not delivered")
                return DispenseOutcome.FAILED
            }
            // A person answers on the page; asked every second until the deadline (+ grace).
            val until = request.deadline_ms + ANSWER_GRACE_MS
            while (System.currentTimeMillis() < until) {
                val a = com.goldsky.ssp.payment.TestSimRepository.answerOf(KIND, request.order_id)
                if (a?.answered == true) {
                    val result = (a.answer?.get("result") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val charge = (a.answer?.get("amount_cents") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    Log.i(TAG, "Simulator: ${request.order_id} -> $result${charge?.let { " (charge $it)" } ?: ""}")
                    return if (result == "DISPENSED") DispenseOutcome(true, charge) else DispenseOutcome.FAILED
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
        /** How long a customer has to take the product once the controller starts (deadline). */
        const val DISPENSE_WINDOW_MS = 90_000L

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
