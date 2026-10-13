package com.goldsky.ssp.feature.apex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryOrderParserTest {

    @Test
    fun testIsDeliveryIntent() {
        assertTrue(DeliveryOrderParser.isDeliveryIntent("生成送货单"))
        assertTrue(DeliveryOrderParser.isDeliveryIntent("帮我建个送货单送到阳光小区"))
        assertTrue(DeliveryOrderParser.isDeliveryIntent("送外卖到长春路88号"))
        assertTrue(DeliveryOrderParser.isDeliveryIntent("New delivery order for Alice"))
    }

    @Test
    fun testParseFullDeliveryOrderSpeech() {
        val input = "生成送货单 送到阳光小区101 客户张先生 电话13800000000 拿铁2杯 美式1杯 备注放门口"
        val proposal = DeliveryOrderParser.parseFromText(input, context = null)

        assertEquals("delivery_order", proposal["kind"]?.jsonPrimitive?.contentOrNull)
        assertEquals("张先生", proposal["customer_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("13800000000", proposal["customer_phone"]?.jsonPrimitive?.contentOrNull)
        assertEquals("阳光小区101", proposal["address"]?.jsonPrimitive?.contentOrNull)
        assertEquals("放门口", proposal["note"]?.jsonPrimitive?.contentOrNull)

        val items = proposal["items"]?.jsonArray
        assertTrue("Expected at least 2 items", (items?.size ?: 0) >= 2)

        val latte = items?.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.contains("拿铁") == true }
        assertTrue("Latte should be parsed", latte != null)
        assertEquals(2, latte?.jsonObject?.get("quantity")?.jsonPrimitive?.contentOrNull?.toIntOrNull())

        val americano = items?.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.contains("美式") == true }
        assertTrue("Americano should be parsed", americano != null)
        assertEquals(1, americano?.jsonObject?.get("quantity")?.jsonPrimitive?.contentOrNull?.toIntOrNull())
    }

    @Test
    fun testParseEmptyProposalFlagsMissingFields() {
        val empty = DeliveryOrderParser.createEmptyProposal()
        val check = (empty["check"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

        assertTrue("Customer name should be flagged", "customer_name" in check)
        assertTrue("Address should be flagged", "address" in check)
        assertTrue("Items should be flagged", "items" in check)
    }
}
