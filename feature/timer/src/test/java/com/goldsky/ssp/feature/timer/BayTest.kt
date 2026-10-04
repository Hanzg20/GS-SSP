package com.goldsky.ssp.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BayTest {

    @Test
    fun `codes match the transactions service_bay check constraint`() {
        // docs/migrations/2026-10-04_transactions_service_bay.sql: CHECK (service_bay IN ('1', '2'))
        assertEquals(listOf("1", "2"), Bay.entries.map { it.code })
    }

    @Test
    fun `unit 1 drives PIN1 (Pulse 1) and unit 2 PIN2 (Pulse 2) by default`() {
        assertEquals(0, Bay.ONE.defaultPort)
        assertEquals(1, Bay.TWO.defaultPort)
    }

    @Test
    fun `remote payload codes resolve, anything else is refused`() {
        assertEquals(Bay.ONE, Bay.fromCode("1"))
        assertEquals(Bay.TWO, Bay.fromCode("2"))
        assertNull(Bay.fromCode(null))
        assertNull(Bay.fromCode(""))
        assertNull(Bay.fromCode("L"))
    }

    @Test
    fun `title is the product name plus the number on the hose`() {
        assertEquals("VACUUM 1", Bay.ONE.title("Vacuum"))
        assertEquals("VACUUM 2", Bay.TWO.title("Vacuum"))
    }
}
