package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeClockMergeTest {
    private val nineAm = "2026-11-01T13:00:00+00:00"
    private val nineAmMs = TimeClockRepository.parseIso(nineAm)!!

    @Test fun cloudOpenShiftsAreOnTheClock() {
        val m = TimeClockRepository.merge(listOf(TimeClockRepository.OpenShift("s1", "A", "a", nineAm)), emptyList())
        assertEquals(mapOf("A" to nineAmMs), m)
    }

    @Test fun queuedPunchesApplyOnTopInOrder() {
        val open = listOf(TimeClockRepository.OpenShift("s1", "A", "a", nineAm))
        val queued = listOf(
            PendingOp(type = "clock_out", staffId = "A", punchRef = "o", punchAt = "2026-11-01T17:00:00Z"),
            PendingOp(type = "clock_in", staffId = "B", punchRef = "i", punchAt = "2026-11-01T14:00:00Z"),
            PendingOp(type = "insert")
        )
        val m = TimeClockRepository.merge(open, queued)
        assertEquals(setOf("B"), m.keys)
        assertEquals(TimeClockRepository.parseIso("2026-11-01T14:00:00Z"), m["B"])
    }

    @Test fun hoursFormat() {
        assertEquals("3:05", TimeClockRepository.hours(185))
        assertEquals("0:00", TimeClockRepository.hours(0))
    }
}
