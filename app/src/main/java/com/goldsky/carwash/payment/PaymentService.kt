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

    interface ResultCallback {
        fun onSuccess()
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
     * [txRefNum] must match the ecr_ref_num of the PENDING transaction row
     * already written by the caller before this is invoked (see
     * MainActivity.initCardPayment) -- pre-writing before ProcessTrans()
     * means a crash between bank approval and our own audit write no longer
     * leaves a charged-but-untracked transaction.
     */
    fun startCardPayment(amountInCents: Int, txRefNum: String, callback: PaymentCallback) {
        if (!KeyHealthMonitor.isPaymentAllowed()) {
            val reason = KeyHealthMonitor.lockReason() ?: "key health check failed"
            Log.e(TAG, "Card payment blocked: $reason")
            callback.onFailure("Payment terminal locked: $reason")
            return
        }

        Log.i(TAG, "Initiating SALE for amount: $amountInCents cents, ref: $txRefNum")

        val posLink = PosLink()
        posLink.commSetting = getCommSetting()

        val request = PaymentRequest().apply {
            transType = 2 // SALE
            tenderType = 1 // CREDIT (Standard for Tap)
            amount = amountInCents.toString()
            ecrRefNum = txRefNum
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
    fun voidTransaction(refNum: String, callback: ResultCallback) {
        if (refNum.isEmpty()) {
            callback.onFailure("Empty refNum")
            return
        }
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
                        callback.onSuccess()
                    } else {
                        val msg = posLink.paymentResponse?.resultMsg ?: result?.msg ?: "VOID declined"
                        Log.e(TAG, "VOID Failed for $refNum: $msg")
                        callback.onFailure(msg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during VOID: ${e.message}")
                withContext(Dispatchers.Main) { callback.onFailure(e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * Refunds a settled transaction. VOID only works pre-settlement; once a
     * batch has closed, the terminal must REFUND instead -- see
     * [voidOrRefund] for the automatic fallback.
     *
     * transType=5 is a placeholder matching common POSLink convention
     * (1=AUTH, 2=SALE, 3=FORCE, 4=VOID, 5=REFUND) but is UNVERIFIED against
     * the real vendor SDK/integration guide (this repo only has local stub
     * classes, not the proprietary PAX jar) -- confirm the exact code before
     * relying on this against real hardware.
     */
    fun refundTransaction(refNum: String, amountInCents: Int, callback: ResultCallback) {
        if (refNum.isEmpty()) {
            callback.onFailure("Empty refNum")
            return
        }
        Log.w(TAG, "Initiating REFUND for RefNum: $refNum")

        val posLink = PosLink()
        posLink.commSetting = getCommSetting()

        val request = PaymentRequest().apply {
            transType = 5 // REFUND (placeholder -- verify against real POSLink integration guide)
            origRefNum = refNum
            amount = amountInCents.toString()
            ecrRefNum = "R" + System.currentTimeMillis()
        }
        posLink.paymentRequest = request

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = posLink.ProcessTrans()
                withContext(Dispatchers.Main) {
                    if (result != null && result.code == ProcessTransResult.ProcessTransExitCode.OK && posLink.paymentResponse?.resultCode == "000000") {
                        Log.i(TAG, "REFUND Successful for $refNum")
                        callback.onSuccess()
                    } else {
                        val msg = posLink.paymentResponse?.resultMsg ?: result?.msg ?: "REFUND declined"
                        Log.e(TAG, "REFUND Failed for $refNum: $msg")
                        callback.onFailure(msg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during REFUND: ${e.message}")
                withContext(Dispatchers.Main) { callback.onFailure(e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * Industrial Fault Tolerance entry point: attempts VOID first (the
     * common case -- hardware failure discovered within the same batch),
     * automatically falls back to REFUND if VOID is declined (e.g. already
     * settled). Reports which method actually succeeded so the caller can
     * record the correct final payment_status ("VOIDED" vs "REFUNDED"); if
     * both fail, [onResolved] fires with success=false and the caller must
     * flag it for manual reconciliation -- money was captured but neither
     * reversal path worked.
     */
    fun voidOrRefund(refNum: String, amountInCents: Int, onResolved: (success: Boolean, method: String) -> Unit) {
        voidTransaction(refNum, object : ResultCallback {
            override fun onSuccess() = onResolved(true, "VOID")
            override fun onFailure(errorMsg: String) {
                Log.w(TAG, "VOID failed ($errorMsg), falling back to REFUND for $refNum")
                refundTransaction(refNum, amountInCents, object : ResultCallback {
                    override fun onSuccess() = onResolved(true, "REFUND")
                    override fun onFailure(errorMsg: String) {
                        Log.e(TAG, "REFUND fallback also failed for $refNum: $errorMsg -- manual reconciliation required")
                        onResolved(false, "NONE")
                    }
                })
            }
        })
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
