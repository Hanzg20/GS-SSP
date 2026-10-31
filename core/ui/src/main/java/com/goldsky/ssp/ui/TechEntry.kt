package com.goldsky.ssp.ui

/**
 * Way into the technician screen on the unattended kiosks (Wash, Timer), owner 2026-10-31:
 * tap the four screen corners in order -- top-left, top-right, bottom-right, bottom-left --
 * within [windowMs], then a 6-8 digit PIN. Replaces "3 taps on the version label", which a
 * hand or water on an outdoor screen could hit (253, 2026-10-30).
 *
 * Feed it every ACTION_DOWN the activity sees; it never consumes the touch.
 */
class CornerSequence(
    private val windowMs: Long = 5_000,
    /** Corner zone: this fraction of the shorter screen side, from each corner. */
    private val cornerFraction: Float = 0.2f,
) {
    private var next = 0
    private var startedAt = 0L

    /** True when this touch completes the sequence. */
    fun onDown(x: Float, y: Float, width: Int, height: Int, nowMs: Long): Boolean {
        val corner = cornerOf(x, y, width, height)
        if (next > 0 && nowMs - startedAt > windowMs) next = 0
        when {
            corner == next -> {
                if (next == 0) startedAt = nowMs
                next++
            }
            corner == 0 -> { next = 1; startedAt = nowMs }
            else -> next = 0
        }
        if (next == ORDER) { next = 0; return true }
        return false
    }

    fun reset() { next = 0 }

    /** 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left, -1 elsewhere. */
    private fun cornerOf(x: Float, y: Float, width: Int, height: Int): Int {
        val zone = minOf(width, height) * cornerFraction
        val left = x <= zone
        val right = x >= width - zone
        val top = y <= zone
        val bottom = y >= height - zone
        return when {
            top && left -> 0
            top && right -> 1
            bottom && right -> 2
            bottom && left -> 3
            else -> -1
        }
    }

    private companion object { const val ORDER = 4 }
}

object TechPin {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 8

    /** The store's PIN (Cael settings.maintenance_pin) is usable: 6-8 digits. */
    fun isValid(configured: String?): Boolean =
        configured != null && configured.length in MIN_LENGTH..MAX_LENGTH && configured.all { it.isDigit() }

    fun accepts(entered: String, configured: String?): Boolean = isValid(configured) && entered == configured
}
