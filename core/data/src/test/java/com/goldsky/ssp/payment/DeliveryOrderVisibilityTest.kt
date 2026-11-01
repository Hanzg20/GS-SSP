package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryOrderVisibilityTest {

    private fun order(id: String, stage: String = "WAITING") =
        DeliveryOrder(id = id, order_no = 1, customer_name = "c", address = "a", status = "OPEN", delivery_stage = stage)

    @Test
    fun `an order collected here stays hidden while the cloud still lists it open`() {
        val (visible, pending) = DeliveryOrderRepository.visibleOpen(listOf(order("A"), order("B")), setOf("A"))
        assertEquals(listOf("B"), visible.map { it.id })
        assertEquals(setOf("A"), pending)
    }

    @Test
    fun `once the cloud no longer lists it open, it is dropped from the pending set`() {
        val (visible, pending) = DeliveryOrderRepository.visibleOpen(listOf(order("B")), setOf("A"))
        assertEquals(listOf("B"), visible.map { it.id })
        assertEquals(emptySet<String>(), pending)
    }

    @Test
    fun `orders out with this driver come first, the rest keep their order`() {
        val list = listOf(order("A"), order("B", "OUT"), order("C"), order("D", "OUT"))
        assertEquals(listOf("B", "D", "A", "C"), DeliveryOrderRepository.listOrder(list).map { it.id })
    }
}
