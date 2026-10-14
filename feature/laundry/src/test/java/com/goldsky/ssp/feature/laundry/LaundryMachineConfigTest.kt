package com.goldsky.ssp.feature.laundry

import com.goldsky.ssp.model.Product
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaundryMachineConfigTest {

    private fun product(id: String, price: Int, attrs: String, vertical: String = "LAUNDRY", active: Boolean = true) =
        Product(id = id, name = "M$id", price_cents = price, vertical_type = vertical,
            attributes = Json.parseToJsonElement(attrs).jsonObject, is_active = active)

    @Test
    fun `a washer is read with defaults and its pulse count`() {
        val m = LaundryMachineConfig.from(product("1", 375, """{"machine_no":3,"relay":3}""")).getOrThrow()
        assertEquals(3, m.machineNo)
        assertEquals(LaundryMachineConfig.Kind.WASHER, m.kind)
        assertEquals(15, m.pulses) // $3.75 in quarters
        assertEquals(100L, m.pulseMs)
    }

    @Test
    fun `a price that is not whole coins, a bad relay or a missing number is refused`() {
        assertTrue(LaundryMachineConfig.from(product("1", 380, """{"machine_no":1,"relay":1}""")).isFailure)
        assertTrue(LaundryMachineConfig.from(product("1", 300, """{"machine_no":1,"relay":9}""")).isFailure)
        assertTrue(LaundryMachineConfig.from(product("1", 300, """{"relay":1}""")).isFailure)
        assertTrue(LaundryMachineConfig.from(product("1", 300, """{"machine_no":1,"relay":1,"kind":"IRON"}""")).isFailure)
        // A $1 coin machine: $3.80 is still refused, $4 is 4 pulses.
        assertEquals(4, LaundryMachineConfig.from(product("1", 400, """{"machine_no":1,"relay":1,"coin_cents":100}""")).getOrThrow().pulses)
        // An explicit pulse_count wins (the July "Standard Wash" demo product: $3.50 = 14).
        assertEquals(14, LaundryMachineConfig.from(product("1", 350, """{"machine_no":1,"relay":1,"pulse_count":14}""")).getOrThrow().pulses)
        assertEquals(3, LaundryMachineConfig.from(product("1", 380, """{"machine_no":1,"relay":1,"pulse_count":3}""")).getOrThrow().pulses)
    }

    @Test
    fun `only this vertical's active products, in machine order, clashes refused`() {
        val (machines, problems) = LaundryMachineConfig.fromProducts(listOf(
            product("a", 300, """{"machine_no":2,"relay":2}"""),
            product("b", 300, """{"machine_no":1,"relay":1}"""),
            product("c", 250, """{"machine_no":5,"relay":5,"kind":"DRYER"}"""),
            product("d", 250, """{"machine_no":6,"relay":5}"""),        // relay 5 twice
            product("e", 600, """{"machine_no":7,"relay":7}""", vertical = "WASH"),
            product("f", 300, """{"machine_no":8,"relay":8}""", active = false),
        ))
        assertEquals(listOf(1, 2), machines.map { it.machineNo })
        assertEquals(2, problems.size)
        assertTrue(problems.all { "used twice" in it })
    }
}
