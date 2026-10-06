package com.goldsky.ssp.payment

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestSaleTest {

    @After
    fun tearDown() = TestSale.disarm()

    @Test
    fun `not armed means a normal sale`() {
        assertNull(TestSale.peek())
        assertNull(TestSale.consume())
    }

    @Test
    fun `armed amount is used by exactly one sale`() {
        TestSale.arm(10)
        assertEquals(10, TestSale.peek())
        assertEquals(10, TestSale.consume())
        assertNull(TestSale.peek())
        assertNull(TestSale.consume())
    }

    @Test
    fun `disarm cancels it`() {
        TestSale.arm(50)
        TestSale.disarm()
        assertNull(TestSale.consume())
    }

    @Test
    fun `test refs are recognisable`() {
        assertTrue(TestSale.isTestRef("TEST_1791149812744"))
        assertFalse(TestSale.isTestRef("TIMER_1791149812744"))
        assertFalse(TestSale.isTestRef(null))
    }

    @Test
    fun `amounts format as dollars and cents`() {
        assertEquals("$0.10", TestSale.format(10))
        assertEquals("$1.00", TestSale.format(100))
    }
}
