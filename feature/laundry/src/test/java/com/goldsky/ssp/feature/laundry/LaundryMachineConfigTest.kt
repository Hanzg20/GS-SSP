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

    private fun machine(price: Int, attrs: String) = LaundryMachineConfig.from(product("1", price, attrs)).getOrThrow()

    @Test
    fun `a washer gets the default temperatures and add-ons, priced per choice`() {
        val m = machine(600, """{"machine_no":3,"relay":3,"size_lb":20}""")
        assertEquals(20, m.sizeLb)
        assertEquals(listOf("Cold", "Warm", "Hot"), m.temps.map { it.label })
        val cold = LaundryMachineConfig.Order(m)
        assertEquals(600, cold.totalCents)
        assertEquals(24, cold.pulses) // $6.00 in quarters
        val hot = LaundryMachineConfig.Order(m, temp = m.temps[2], addons = setOf(m.addons[0]))
        assertEquals(750, hot.totalCents) // 6.00 + 1.00 hot + 0.50 heavy soil
        assertEquals(30, hot.pulses)
        assertEquals("Hot · Heavy soil", hot.summary)
    }

    @Test
    fun `a dryer sells time in steps of its price`() {
        val m = machine(100, """{"machine_no":6,"relay":6,"kind":"DRYER","dry_minutes":12}""")
        assertEquals(listOf(12, 24, 36, 48), m.dryTimes.map { it.minutes })
        val o = LaundryMachineConfig.Order(m)               // default: the third step
        assertEquals(36, o.dry?.minutes)
        assertEquals(300, o.totalCents)
        assertEquals(12, o.pulses)
        assertEquals("36 min", o.summary)
    }

    @Test
    fun `options can be set per machine, and must be whole coins`() {
        val m = machine(500, """{"machine_no":1,"relay":1,"temps":[{"name":"Cold","add_cents":0},{"name":"Hot","add_cents":75}],"addons":[]}""")
        assertEquals(listOf("Cold", "Hot"), m.temps.map { it.label })
        assertTrue(m.addons.isEmpty())
        assertTrue(LaundryMachineConfig.from(product("1", 500, """{"machine_no":1,"relay":1,"addons":[{"name":"X","add_cents":30}]}""")).isFailure)
        assertTrue(LaundryMachineConfig.from(product("1", 380, """{"machine_no":1,"relay":1}""")).isFailure)
    }

    @Test
    fun `a single-pulse machine has a fixed price and no options`() {
        val m = machine(350, """{"machine_no":1,"relay":1,"pulse_count":1}""")
        assertTrue(m.temps.isEmpty() && m.addons.isEmpty() && m.dryTimes.isEmpty())
        val o = LaundryMachineConfig.Order(m)
        assertEquals(350, o.totalCents)
        assertEquals(1, o.pulses)
        // The July "Standard Wash" demo product: an explicit 14 pulses for $3.50.
        assertEquals(14, LaundryMachineConfig.Order(machine(350, """{"machine_no":1,"relay":1,"pulse_count":14}""")).pulses)
    }

    @Test
    fun `a bad relay, a missing number or a bad kind is refused`() {
        assertTrue(LaundryMachineConfig.from(product("1", 300, """{"machine_no":1,"relay":9}""")).isFailure)
        assertTrue(LaundryMachineConfig.from(product("1", 300, """{"relay":1}""")).isFailure)
        assertTrue(LaundryMachineConfig.from(product("1", 300, """{"machine_no":1,"relay":1,"kind":"IRON"}""")).isFailure)
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

    @Test
    fun `the demo laundromat uses Toronto prices`() {
        val d = LaundryMachineConfig.DEMO
        assertEquals(600, d[0].priceCents); assertEquals(20, d[0].sizeLb)
        assertEquals(1000, d[3].priceCents); assertEquals(40, d[3].sizeLb)
        assertEquals(100, d[5].priceCents); assertEquals(LaundryMachineConfig.Kind.DRYER, d[5].kind)
    }
}
