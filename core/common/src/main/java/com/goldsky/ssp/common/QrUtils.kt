package com.goldsky.ssp.common

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrUtils {
    /**
     * Generates a QR code bitmap from the given content.
     */
    fun generateQrCode(content: String, width: Int, height: Int): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            // ZXing's own default quiet zone is 4 modules, which reads as a
            // large white border baked into the bitmap itself (on top of
            // whatever padding the ImageView adds) -- shrunk to 1 module
            // (still non-zero, so scanners that rely on a contrast boundary
            // to lock on still work) so the code renders as large and dense
            // as possible at a fixed pixel size, per on-site feedback that
            // the old margin made it harder to scan, not easier.
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
