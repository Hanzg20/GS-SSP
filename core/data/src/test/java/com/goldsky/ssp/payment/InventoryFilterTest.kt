package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryFilterTest {
    private val soy = InventoryRepository.Item("1", "Light Soy Sauce", barcode = "012345678905", stock_qty = 3, low_stock_qty = 5)
    private val rice = InventoryRepository.Item("2", "Jasmine Rice", stock_qty = 40)
    private val tofu = InventoryRepository.Item("3", "Tofu")

    @Test fun lowOutAndUntracked() {
        assertTrue(soy.low); assertFalse(soy.out)
        assertFalse(rice.low)
        assertFalse(tofu.tracked); assertFalse(tofu.low); assertFalse(tofu.out)
        assertTrue(InventoryRepository.Item("4", "x", stock_qty = -1).out)
    }

    @Test fun searchByNameOrBarcode() {
        val all = listOf(soy, rice, tofu)
        assertEquals(listOf("2"), InventoryRepository.filter(all, "rice", false).map { it.id })
        assertEquals(listOf("1"), InventoryRepository.filter(all, "012345678905", false).map { it.id })
        assertEquals(listOf("1"), InventoryRepository.filter(all, "", true).map { it.id })
        assertEquals(3, InventoryRepository.filter(all, " ", false).size)
    }
}
