package com.goldsky.ssp.payment.hardware.wizarpos

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TCP Client for WizarPOS PAYWizard Local Integration.
 * Implements P3 (Core Protocol) framing: STX | VER | CTRL | LEN | JSON | ETX | BCC
 */
object WizarPosSocketClient {
    private const val TAG = "WizarPosSocket"
    private const val HOST = "127.0.0.1"
    private const val PORT = 6666
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 60000 
    
    private var sequenceNumber = 1

    /**
     * Sends a request using P3 framing.
     */
    suspend fun sendRequest(jsonPayload: String, ctrlPath: Byte = WizarPosP3Protocol.CTRL_FROM_CASHIER): String? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            Log.d(TAG, "Connecting to local PAYWizard at $HOST:$PORT")
            socket = Socket()
            socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val outputStream = socket.getOutputStream()
            val inputStream = socket.getInputStream()

            // 1. Pack and Send P3 Frame
            val frame = WizarPosP3Protocol.pack(ctrlPath, sequenceNumber++, jsonPayload)
            outputStream.write(frame)
            outputStream.flush()
            Log.i(TAG, "Sent P3 Request (${frame.size} bytes): $jsonPayload")

            // 2. Read Response with STX alignment
            val responseFrame = readP3Frame(inputStream) ?: return@withContext null
            
            // 3. Unpack JSON
            val responseJson = WizarPosP3Protocol.unpack(responseFrame)
            Log.i(TAG, "Received P3 Response: $responseJson")

            responseJson
        } catch (e: Exception) {
            Log.e(TAG, "Local P3 Socket communication failed: ${e.message}")
            null
        } finally {
            try { socket?.close() } catch (e: Exception) {}
        }
    }

    /**
     * Reads a full P3 frame from the input stream.
     * Searches for STX, reads header, then reads the body based on length.
     */
    private fun readP3Frame(inputStream: InputStream): ByteArray? {
        try {
            // A. Find STX (0x02)
            var b: Int
            while (true) {
                b = inputStream.read()
                if (b == -1) return null
                if (b.toByte() == WizarPosP3Protocol.STX) break
            }
            
            // B. Read Header (VER(1) + CTRL(4) + LEN(2) = 7 bytes)
            val header = readFully(inputStream, 7) ?: return null
            val contentLen = ByteBuffer.wrap(header, 5, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            
            // C. Read Body (CONTENT(N) + ETX(1) + BCC(1) = contentLen + 2 bytes)
            val body = readFully(inputStream, contentLen + 2) ?: return null
            
            // D. Assemble Full Frame
            val fullFrame = ByteArray(contentLen + 11)
            fullFrame[0] = WizarPosP3Protocol.STX
            System.arraycopy(header, 0, fullFrame, 1, 7)
            System.arraycopy(body, 0, fullFrame, 8, body.size)
            
            return fullFrame
        } catch (e: Exception) {
            Log.e(TAG, "Error reading P3 frame: ${e.message}")
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
}
