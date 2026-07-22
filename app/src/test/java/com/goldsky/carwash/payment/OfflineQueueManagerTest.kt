package com.goldsky.carwash.payment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineQueueManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun record(ref: String) = TransactionRecord(
        device_sn = "TEST_SN",
        amount = 500,
        payment_status = "PAID",
        ecr_ref_num = ref
    )

    @Test
    fun `draining an empty queue calls send zero times`() = runBlocking {
        val dir = tempFolder.newFolder()
        var calls = 0
        OfflineQueueManager.drain(dir) { calls++; true }
        assertEquals(0, calls)
    }

    @Test
    fun `enqueued ops are replayed and removed on success`() = runBlocking {
        val dir = tempFolder.newFolder()
        OfflineQueueManager.enqueue(dir, PendingOp(type = "insert", record = record("TX1")))
        OfflineQueueManager.enqueue(dir, PendingOp(type = "update_hw", ecrRefNum = "TX1", status = "ACK_RECEIVED"))

        assertEquals(2, OfflineQueueManager.size(dir))

        val sent = mutableListOf<PendingOp>()
        OfflineQueueManager.drain(dir) { op -> sent.add(op); true }

        assertEquals(2, sent.size)
        assertEquals(0, OfflineQueueManager.size(dir))
    }

    @Test
    fun `ops that fail to send stay queued for the next drain`() = runBlocking {
        val dir = tempFolder.newFolder()
        OfflineQueueManager.enqueue(dir, PendingOp(type = "insert", record = record("TX_FAIL")))
        OfflineQueueManager.enqueue(dir, PendingOp(type = "insert", record = record("TX_OK")))

        OfflineQueueManager.drain(dir) { op -> op.record?.ecr_ref_num != "TX_FAIL" }

        assertEquals(1, OfflineQueueManager.size(dir))
        val remaining = OfflineQueueManager.readFile(OfflineQueueManager.queueFile(dir))
        assertEquals("TX_FAIL", remaining.single().record?.ecr_ref_num)
    }

    @Test
    fun `an exception from send leaves the op queued rather than crashing`() = runBlocking {
        val dir = tempFolder.newFolder()
        OfflineQueueManager.enqueue(dir, PendingOp(type = "insert", record = record("TX_THROWS")))

        OfflineQueueManager.drain(dir) { throw RuntimeException("network down") }

        assertEquals(1, OfflineQueueManager.size(dir))
    }

    @Test
    fun `a corrupted queue file is treated as empty instead of crashing`() {
        val dir = tempFolder.newFolder()
        OfflineQueueManager.queueFile(dir).writeText("{ not valid json ]")
        assertTrue(OfflineQueueManager.readFile(OfflineQueueManager.queueFile(dir)).isEmpty())
    }
}
