package com.goldsky.carwash.payment

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.pax.poslink.*

/**
 * Production-grade Service to handle PAX card transactions and mobile QR code payments.
 */
object PaymentService {
    private const val TAG = "PaymentService"

    interface PaymentCallback {
        fun onSuccess(txId: String, refNum: String)
        fun onFailure(errorMsg: String)
    }

    /**
     * Initializes communication settings for internal AIDL connection on PAX IM30.
     */
    private fun getCommSetting(): CommSetting {
        return CommSetting().apply {
            commType = "AIDL"
            destIP = "127.0.0.1"
            destPort = "10009"
            timeout = "60000"
        }
    }

    /**
     * Triggers the card payment flow (Tap/Insert/Swipe) using PAX POSLink SALE.
     */
    fun startCardPayment(amountInCents: Int, callback: PaymentCallback) {
        if (!KeyHealthMonitor.isPaymentAllowed()) {
            val reason = KeyHealthMonitor.lockReason() ?: "key health check failed"
            Log.e(TAG, "Card payment blocked: $reason")
            callback.onFailure("Payment terminal locked: $reason")
            return
        }

        Log.i(TAG, "Initiating SALE for amount: $amountInCents cents")

        val posLink = PosLink()
        posLink.commSetting = getCommSetting()

        val request = PaymentRequest().apply {
            transType = 2 // SALE
            tenderType = 1 // CREDIT (Standard for Tap)
            amount = amountInCents.toString()
            ecrRefNum = System.currentTimeMillis().toString()
        }
        posLink.paymentRequest = request

        // Run transaction in IO thread
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                
                withContext(Dispatchers.Main) {
                    val response = posLink.paymentResponse
                    KeyHealthMonitor.recordResult(response?.resultCode, response?.resultMsg ?: result?.msg)

                    if (result != null && result.code == ProcessTransResult.ProcessTransExitCode.OK) {
                        if (response != null && response.resultCode == "000000") {
                            Log.d(TAG, "Payment Approved. RefNum: ${response.refNum}")
                            callback.onSuccess(response.authCode ?: "", response.refNum ?: "")
                        } else {
                            val msg = response?.resultMsg ?: "Declined"
                            Log.w(TAG, "Payment Declined: $msg")
                            callback.onFailure(msg)
                        }
                    } else {
                        val msg = result?.msg ?: "Communication Error"
                        Log.e(TAG, "SDK Error: $msg")
                        callback.onFailure(msg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onFailure(e.message ?: "Unknown Error")
                }
            }
        }
    }

    /**
     * Voids a transaction (Industrial Fault Tolerance).
     * Called when hardware fails after successful bank authorization.
     */
    fun voidTransaction(refNum: String) {
        if (refNum.isEmpty()) return
        Log.w(TAG, "Initiating VOID for RefNum: $refNum")
        
        val posLink = PosLink()
        posLink.commSetting = getCommSetting()

        val request = PaymentRequest().apply {
            transType = 4 // VOID
            origRefNum = refNum
            ecrRefNum = "V" + System.currentTimeMillis()
        }
        posLink.paymentRequest = request

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    if (result != null && result.code == ProcessTransResult.ProcessTransExitCode.OK && posLink.paymentResponse?.resultCode == "000000") {
                        Log.i(TAG, "VOID Successful for $refNum")
                    } else {
                        Log.e(TAG, "VOID Failed for $refNum. Manual intervention required.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during VOID: ${e.message}")
            }
        }
    }

    /**
     * Generates a high-contrast QR Code bitmap from a URL using ZXing.
     */
    fun generateQrCode(url: String, width: Int = 300, height: Int = 300): Bitmap? {
        return try {
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(
                url, BarcodeFormat.QR_CODE, width, height
            )
            val bitMatrixWidth = bitMatrix.width
            val bitMatrixHeight = bitMatrix.height
            val pixels = IntArray(bitMatrixWidth * bitMatrixHeight)

            for (y in 0 until bitMatrixHeight) {
                val offset = y * bitMatrixWidth
                for (x in 0 until bitMatrixWidth) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }

            val bitmap = Bitmap.createBitmap(bitMatrixWidth, bitMatrixHeight, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, bitMatrixWidth, bitMatrixHeight)
            bitmap
        } catch (e: WriterException) {
            Log.e(TAG, "Failed to generate QR Code: ${e.message}")
            null
        }
    }

}
