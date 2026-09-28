package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCommandTimestampTest {
    // 2026-09-28T20:08:18Z in epoch millis.
    private val expected = 1790626098000L

    @Test
    fun parsesPostgrestTimestamps() {
        // Shapes seen in device_commands.created_at.
        assertEquals(expected, RemoteCommandManager.parseTimestamp("2026-09-28T16:08:18.914220-04:00"))
        assertEquals(expected, RemoteCommandManager.parseTimestamp("2026-09-28T16:08:18-04:00"))
        assertEquals(expected, RemoteCommandManager.parseTimestamp("2026-09-28T20:08:18+00:00"))
        assertEquals(expected, RemoteCommandManager.parseTimestamp("2026-09-28T20:08:18.5Z"))
    }

    @Test
    fun unparseableIsZero() {
        assertEquals(0L, RemoteCommandManager.parseTimestamp("not a time"))
    }
}
