package com.goldsky.ssp.serial

/**
 * Utility for industrial checksum algorithms.
 */
object CrcUtils {
    
    /**
     * Calculates CRC16-CCITT (0x1021) for the given byte array.
     */
    fun crc16ccitt(bytes: ByteArray): Int {
        var crc = 0xFFFF
        val polynomial = 0x1021

        for (b in bytes) {
            for (i in 0..7) {
                val bit = (b.toInt() shr (7 - i) and 1) == 1
                val c15 = (crc shr 15 and 1) == 1
                crc = crc shl 1
                if (c15 xor bit) crc = crc xor polynomial
            }
        }

        return crc and 0xFFFF
    }
}
