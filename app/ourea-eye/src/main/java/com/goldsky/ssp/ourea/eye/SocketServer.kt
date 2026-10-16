package com.goldsky.ssp.ourea.eye

import android.util.Log
import com.goldsky.ssp.payment.wizarpos.WizarPosP3Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CfdItemData(val name: String, val qty: Int, val priceCents: Int)

sealed class CfdDisplayState {
    object Idle : CfdDisplayState()
    /** [subtotalCents]: after [discountCents], before tax. */
    data class Cart(val items: List<CfdItemData>, val subtotalCents: Int, val taxCents: Int, val totalCents: Int, val discountCents: Int = 0) : CfdDisplayState()
    /** [cardPrompt] false: the amount only (tip chosen, staff may still take cash). */
    data class Payment(val amountCents: Int, val cardPrompt: Boolean = true) : CfdDisplayState()
    /** The customer chooses a tip ([basisCents]: before tax); [tipId] is Ourea's request. */
    data class Tip(val tipId: String, val basisCents: Int, val totalCents: Int, val cardNext: Boolean = false, val review: com.goldsky.ssp.common.Tipping.Review? = null, val askTip: Boolean = true) : CfdDisplayState()
    /** [orderNumber]: a quick-service counter's call number, shown big. */
    data class ThankYou(val orderNumber: Int? = null) : CfdDisplayState()
}

object SocketServerManager {
    private const val TAG = "OureaEyeSocket"
    private const val PORT = 6032
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    var onStateChanged: ((CfdDisplayState) -> Unit)? = null

    // For the technician panel: is the listener up, and who talked to it last.
    @Volatile var isListening = false; private set
    @Volatile var lastClientIp: String? = null; private set
    @Volatile var lastCommand: String? = null; private set
    @Volatile var lastCommandAt = 0L; private set
    const val LISTEN_PORT = PORT
    var onRotationChanged: ((Int) -> Unit)? = null

    // The tip request on screen and, once tapped, the customer's choice. Ourea's
    // TipStatus waits on [tipChoice] (up to TIP_HOLD_MS), so the answer goes back
    // the moment a tile is tapped.
    @Volatile private var tipId: String? = null
    private val tipChoice = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, Int>?>(null)
    private const val TIP_HOLD_MS = 9_000L
    /** The last cart shown, to go back to when Ourea cancels the tip. */
    @Volatile private var lastCart: CfdDisplayState.Cart? = null

    /** The customer tapped a tip on the screen (main thread). */
    fun onTipChosen(id: String, tipCents: Int) {
        if (tipId != id) return
        tipChoice.value = id to tipCents
    }

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(PORT)
                isListening = true
                Log.i(TAG, "Ourea Eye TCP Server started on port $PORT")
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            } finally {
                isListening = false
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val inputStream = socket.getInputStream()
            val frame = readP3Frame(inputStream) ?: return
            val jsonStr = WizarPosP3Protocol.unpack(frame) ?: return
            Log.i(TAG, "Received P3 JSON: $jsonStr")
            lastClientIp = socket.inetAddress?.hostAddress

            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val transType = root["TransType"]?.jsonPrimitive?.content ?: ""
            lastCommand = transType
            lastCommandAt = System.currentTimeMillis()

            var response = "{\"ResultCode\":\"0\"}"
            when (transType) {
                "DisplayTip" -> {
                    val id = root["TipId"]?.jsonPrimitive?.content ?: ""
                    val basis = root["BasisCents"]?.jsonPrimitive?.int ?: 0
                    val total = root["TotalCents"]?.jsonPrimitive?.int ?: 0
                    tipId = id
                    tipChoice.value = null
                    withContext(Dispatchers.Main) {
                        // CardNext: Ourea starts the card sale the moment the tip is in.
                        // Review: a dine-in bill to check before the tip. AskTip false: the bill only.
                        val review = (root["Review"] as? JsonObject)?.let { r ->
                            com.goldsky.ssp.common.Tipping.Review(
                                title = r["Title"]?.jsonPrimitive?.content ?: "",
                                lines = (r["Items"] as? JsonArray).orEmpty().map { el ->
                                    val o = el.jsonObject
                                    com.goldsky.ssp.common.Tipping.ReviewLine(
                                        o["name"]?.jsonPrimitive?.content ?: "", o["qty"]?.jsonPrimitive?.intOrNull ?: 1, o["cents"]?.jsonPrimitive?.intOrNull ?: 0
                                    )
                                },
                                subtotalCents = r["SubtotalCents"]?.jsonPrimitive?.intOrNull ?: 0,
                                taxCents = r["TaxCents"]?.jsonPrimitive?.intOrNull ?: 0,
                                totalCents = r["TotalCents"]?.jsonPrimitive?.intOrNull ?: total,
                                paidCents = r["PaidCents"]?.jsonPrimitive?.intOrNull ?: 0,
                                dueCents = r["DueCents"]?.jsonPrimitive?.intOrNull ?: total,
                                dueLabel = r["DueLabel"]?.jsonPrimitive?.content
                            )
                        }
                        onStateChanged?.invoke(CfdDisplayState.Tip(id, basis, total, root["CardNext"]?.jsonPrimitive?.booleanOrNull ?: false, review,
                            root["AskTip"]?.jsonPrimitive?.booleanOrNull ?: true))
                    }
                    response = "{\"ResultCode\":\"0\",\"Tip\":\"READY\"}"
                }
                "TipStatus" -> {
                    val id = root["TipId"]?.jsonPrimitive?.content
                    val chosen = if (id != null && id == tipId) {
                        withTimeoutOrNull(TIP_HOLD_MS) { tipChoice.first { it?.first == id } }
                    } else null
                    response = when {
                        chosen != null -> {
                            tipId = null
                            "{\"ResultCode\":\"0\",\"Status\":\"CHOSEN\",\"TipCents\":${chosen.second}}"
                        }
                        id != null && id == tipId -> "{\"ResultCode\":\"0\",\"Status\":\"WAITING\"}"
                        else -> "{\"ResultCode\":\"0\",\"Status\":\"GONE\"}"
                    }
                }
                "CancelTip" -> {
                    val id = root["TipId"]?.jsonPrimitive?.content
                    if (id == null || id == tipId) {
                        tipId = null
                        val back = lastCart ?: CfdDisplayState.Idle
                        withContext(Dispatchers.Main) { onStateChanged?.invoke(back) }
                    }
                }
                "DisplayIdle" -> {
                    lastCart = null
                    withContext(Dispatchers.Main) {
                        onStateChanged?.invoke(CfdDisplayState.Idle)
                    }
                }
                "DisplayPayment" -> {
                    val amount = root["AmountCents"]?.jsonPrimitive?.int ?: 0
                    withContext(Dispatchers.Main) {
                        onStateChanged?.invoke(CfdDisplayState.Payment(amount))
                    }
                }
                "DisplayThankYou" -> {
                    withContext(Dispatchers.Main) {
                        onStateChanged?.invoke(CfdDisplayState.ThankYou(root["OrderNumber"]?.jsonPrimitive?.intOrNull))
                    }
                }
                "DisplayRotate" -> {
                    val rotation = root["Rotation"]?.jsonPrimitive?.int ?: 0
                    withContext(Dispatchers.Main) {
                        onRotationChanged?.invoke(rotation)
                    }
                }
                "DisplayCart" -> {
                    val sub = root["SubtotalCents"]?.jsonPrimitive?.int ?: 0
                    val tax = root["TaxCents"]?.jsonPrimitive?.int ?: 0
                    val total = root["TotalCents"]?.jsonPrimitive?.int ?: (sub + tax)
                    val itemsJson = root["Items"]?.jsonArray ?: emptyList()
                    val items = itemsJson.map { el ->
                        val obj = el.jsonObject
                        CfdItemData(
                            name = obj["name"]?.jsonPrimitive?.content ?: "Item",
                            qty = obj["qty"]?.jsonPrimitive?.int ?: 1,
                            priceCents = obj["priceCents"]?.jsonPrimitive?.int ?: 0
                        )
                    }
                    val cart = CfdDisplayState.Cart(items, sub, tax, total, root["DiscountCents"]?.jsonPrimitive?.intOrNull ?: 0)
                    lastCart = cart
                    withContext(Dispatchers.Main) {
                        onStateChanged?.invoke(cart)
                    }
                }
            }

            val out = socket.getOutputStream()
            val respBytes = WizarPosP3Protocol.pack(WizarPosP3Protocol.CTRL_FROM_POS, 1, response)
            out.write(respBytes)
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Client handling error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readP3Frame(inputStream: InputStream): ByteArray? {
        try {
            var b: Int
            while (true) {
                b = inputStream.read()
                if (b == -1) return null
                if (b.toByte() == WizarPosP3Protocol.STX) break
            }
            val header = readFully(inputStream, 7) ?: return null
            val contentLen = ByteBuffer.wrap(header, 5, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            val body = readFully(inputStream, contentLen + 2) ?: return null
            val fullFrame = ByteArray(contentLen + 10)
            fullFrame[0] = WizarPosP3Protocol.STX
            System.arraycopy(header, 0, fullFrame, 1, 7)
            System.arraycopy(body, 0, fullFrame, 8, body.size)
            return fullFrame
        } catch (e: Exception) {
            return null
        }
    }

    private fun readFully(inputStream: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = inputStream.read(buffer, totalRead, length - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        return buffer
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverJob?.cancel()
    }
}
