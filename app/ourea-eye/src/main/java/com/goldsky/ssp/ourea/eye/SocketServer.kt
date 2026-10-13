package com.goldsky.ssp.ourea.eye

import android.util.Log
import com.goldsky.ssp.payment.wizarpos.WizarPosP3Protocol
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CfdItemData(val name: String, val qty: Int, val priceCents: Int)

sealed class CfdDisplayState {
    object Idle : CfdDisplayState()
    data class Cart(val items: List<CfdItemData>, val subtotalCents: Int, val taxCents: Int, val totalCents: Int) : CfdDisplayState()
    data class Payment(val amountCents: Int) : CfdDisplayState()
    object ThankYou : CfdDisplayState()
}

object SocketServerManager {
    private const val TAG = "OureaEyeSocket"
    private const val PORT = 6032
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    var onStateChanged: ((CfdDisplayState) -> Unit)? = null
    var onRotationChanged: ((Int) -> Unit)? = null

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.i(TAG, "Ourea Eye TCP Server started on port $PORT")
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
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

            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val transType = root["TransType"]?.jsonPrimitive?.content ?: ""

            when (transType) {
                "DisplayIdle" -> {
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
                        onStateChanged?.invoke(CfdDisplayState.ThankYou)
                    }
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(3000)
                        onStateChanged?.invoke(CfdDisplayState.Idle)
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
                    withContext(Dispatchers.Main) {
                        onStateChanged?.invoke(CfdDisplayState.Cart(items, sub, tax, total))
                    }
                }
            }

            val out = socket.getOutputStream()
            val respBytes = WizarPosP3Protocol.pack(WizarPosP3Protocol.CTRL_FROM_POS, 1, "{\"ResultCode\":\"0\"}")
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
