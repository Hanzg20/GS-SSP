package com.goldsky.ssp.flex.logic

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * FlexController.Http against a scripted controller on localhost: v1.1 stages and money rules
 * (docs/partner/flex_controller_api_v1.1.md), and a v1 controller still understood.
 */
class FlexControllerHttpTest {

    /** A bare HTTP/1.1 server on localhost (the JDK's HttpServer isn't on the Android test classpath). */
    private lateinit var server: ServerSocket
    /** What POST /flex/v1/dispense answers: code to body. */
    private var postAnswer: Pair<Int, String> = 202 to ""
    /** GET /flex/v1/orders/{id} answers these in turn; the last one repeats. */
    private var orderAnswers: List<Pair<Int, String>> = emptyList()
    @Volatile private var gets = 0

    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { c ->
                    val input = c.getInputStream().bufferedReader(Charsets.UTF_8)
                    val path = input.readLine()?.split(" ")?.getOrNull(1).orEmpty()
                    var length = 0
                    while (true) {
                        val h = input.readLine() ?: break
                        if (h.isEmpty()) break
                        if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(":").trim().toInt()
                    }
                    repeat(length) { input.read() }
                    val a = when {
                        path.startsWith("/flex/v1/dispense") -> postAnswer
                        path.startsWith("/flex/v1/orders/") -> orderAnswers[minOf(gets, orderAnswers.size - 1)].also { gets++ }
                        else -> 404 to ""
                    }
                    val body = a.second.toByteArray(Charsets.UTF_8)
                    val out = c.getOutputStream()
                    val head = listOf("HTTP/1.1 ${a.first} X", "Content-Type: application/json",
                        "Content-Length: ${body.size}", "Connection: close", "", "").joinToString(CRLF)
                    out.write(head.toByteArray())
                    out.write(body)
                    out.flush()
                }
            }
        }
    }

    @After fun stop() = server.close()

    private val controller get() = FlexController.Http("http://127.0.0.1:${server.localPort}", null)

    private fun request(completeInMs: Long = 60_000) = System.currentTimeMillis().let { now ->
        FlexController.DispenseRequest(
            order_id = ID, product_id = null, slot = "3", name = "3 Bags", quantity = 3, amount_cents = 2100,
            currency = "CAD", payment_method = "CARD", payment_flow = "PREAUTH", terminal_sn = "SN",
            deadline_ms = now + 30_000, charge_by_quantity = mapOf("0" to 0, "1" to 800, "2" to 1500, "3" to 2100),
            complete_by_ms = now + completeInMs,
        )
    }

    private fun order(state: String, extra: String = "") = 200 to """{"order_id":"$ID","state":"$state"$extra}"""

    @Test fun stagesThenTwoOfThreeTaken() = runBlocking {
        postAnswer = 202 to """{"order_id":"$ID","state":"ACCEPTED"}"""
        orderAnswers = listOf(
            order("OPENING"),
            500 to "", // a missed answer is just asked again
            order("OPEN", ""","pickup_remaining_ms":60000"""),
            order("OPEN", ""","pickup_remaining_ms":59500"""),
            order("CLOSING", ""","pickup_end":"TAKEN""""),
            order("COMPLETED", ""","pickup_end":"TAKEN","taken_quantity":2,"start_weight_g":500000,"end_weight_g":484000"""),
        )
        val seen = mutableListOf<FlexController.Progress>()
        val out = controller.dispense(request()) { seen += it }
        assertTrue(out.dispensed)
        assertEquals(2, out.takenQuantity)
        assertEquals("TAKEN", out.pickupEnd)
        assertEquals(484000L, out.endWeightG)
        assertTrue(seen.first() is FlexController.Progress.Opening)
        assertEquals(60_000L, (seen.first { it is FlexController.Progress.Open } as FlexController.Progress.Open).remainingMs)
        assertEquals(FlexController.Progress.Closing(false), seen.last())
    }

    @Test fun timeoutWithNothingTakenKeepsNothing() = runBlocking {
        postAnswer = 202 to """{"order_id":"$ID","state":"ACCEPTED"}"""
        orderAnswers = listOf(
            order("CLOSING", ""","pickup_end":"TIMEOUT""""),
            order("COMPLETED", ""","pickup_end":"TIMEOUT","taken_quantity":0"""),
        )
        val seen = mutableListOf<FlexController.Progress>()
        val out = controller.dispense(request()) { seen += it }
        assertFalse(out.dispensed)
        assertEquals(0, out.takenQuantity)
        assertEquals(FlexController.Progress.Closing(true), seen.single())
    }

    @Test fun completedWithoutTakenQuantityIsGivenBack() = runBlocking {
        postAnswer = 202 to """{"order_id":"$ID","state":"ACCEPTED"}"""
        orderAnswers = listOf(order("COMPLETED"))
        assertFalse(controller.dispense(request()).dispensed)
    }

    @Test fun refusedAtOnce() = runBlocking {
        postAnswer = 200 to """{"order_id":"$ID","state":"FAILED","reason":"shutter fault"}"""
        val out = controller.dispense(request())
        assertFalse(out.dispensed)
        assertEquals(0, gets)
    }

    @Test fun noFinalStateByCompleteByIsGivenBack() = runBlocking {
        postAnswer = 202 to """{"order_id":"$ID","state":"ACCEPTED"}"""
        orderAnswers = listOf(order("OPEN", ""","pickup_remaining_ms":1000"""))
        assertFalse(controller.dispense(request(completeInMs = 1_500)).dispensed)
    }

    @Test fun anotherOrdersAnswerIsIgnored() = runBlocking {
        postAnswer = 202 to """{"order_id":"$ID","state":"ACCEPTED"}"""
        orderAnswers = listOf(200 to """{"order_id":"FLX-OTHER","state":"COMPLETED","taken_quantity":3}""")
        assertFalse(controller.dispense(request(completeInMs = 1_500)).dispensed)
    }

    @Test fun v1ControllerStillWorks() = runBlocking {
        postAnswer = 200 to """{"order_id":"$ID","result":"DISPENSED","amount_cents":1250}"""
        val out = controller.dispense(request())
        assertTrue(out.dispensed)
        assertEquals(1250, out.chargeCents)
        assertNull(out.takenQuantity)
        assertEquals(0, gets)
    }

    @Test fun v1FailedIsGivenBack() = runBlocking {
        postAnswer = 200 to """{"order_id":"$ID","result":"FAILED","reason":"door"}"""
        assertFalse(controller.dispense(request()).dispensed)
    }

    private companion object {
        const val ID = "FLX-1795010000000"
        const val CRLF = "\r\n"
    }
}
