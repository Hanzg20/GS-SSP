package com.goldsky.ssp.common

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrUtils {
    /**
     * Generates a QR code bitmap from the given content.
     */
    /**
     * [forLogo]: highest error correction (H, ~30% recoverable) so a logo can
     * cover the centre and the code still scans (VIP member pass).
     */
    fun generateQrCode(content: String, width: Int, height: Int, forLogo: Boolean = false): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            // ZXing's own default quiet zone is 4 modules, which reads as a
            // large white border baked into the bitmap itself (on top of
            // whatever padding the ImageView adds) -- shrunk to 1 module
            // (still non-zero, so scanners that rely on a contrast boundary
            // to lock on still work) so the code renders as large and dense
            // as possible at a fixed pixel size, per on-site feedback that
            // the old margin made it harder to scan, not easier.
            // The VIP pass (forLogo) gets 2 modules: customers photograph the
            // terminal screen and a wider quiet zone helps the scanner lock on
            // to that photo (simulated 2026-10-07, see VipCardView).
            val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to if (forLogo) 2 else 1)
            if (forLogo) hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H
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
