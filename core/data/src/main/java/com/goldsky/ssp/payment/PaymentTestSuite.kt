package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WizarPOS / Nuvei PAYWizard AIDL test cases that apply to Aegis (see the
 * 2026-10-08 comparison doc for Maggie): purchases incl. max/over-max
 * amount, reversal, refund, the 9001-9005 decline amounts, purchase +
 * query by TransIndexCode, timeout, chip, swipe, settle.
 *
 * Runs only on a terminal flagged devices.payment_test_enabled (a UAT
 * terminal) -- these amounts are real money on a production host.
 */
object PaymentTestSuite {
    private const val TAG = "PaymentTestSuite"
    private val json = Json { ignoreUnknownKeys = true }

    /** What the case expects back. [descHint]: words RespDesc should contain (any of, case-insensitive). */
    data class Expect(val approved: Boolean, val descHint: List<String> = emptyList(), val entryModes: Set<Int> = emptySet())

    data class Case(
        val id: String,
        val title: String,
        /** What the technician does (shown on screen). */
        val instruction: String,
        val expect: Expect,
        /** Case ids whose results this one needs (TransId / TransIndexCode). */
        val needs: List<String> = emptyList(),
        /** Request fields; [prev] = earlier results by case id. */
        val request: (ref: String, prev: Map<String, Result>) -> Map<String, String>?,
    )

    enum class Status { PASS, FAIL, NO_RESPONSE, BLOCKED }

    data class Result(
        val caseId: String,
        val status: Status,
        val note: String,
        val request: Map<String, String>,
        val response: String?,
        val at: Long = System.currentTimeMillis(),
    ) {
        private val root: JsonObject? = response?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        fun field(k: String): String? = root?.get(k)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
        val transId get() = field("TransID") ?: field("TransId")
        val transIndexCode get() = request["TransIndexCode"]
    }

    private fun purchase(amount: String) = { ref: String, _: Map<String, Result> ->
        mapOf("TransType" to "Purchase", "TransIndexCode" to ref, "TransAmount" to amount)
    }

    val cases: List<Case> = listOf(
        Case("PT-01", "Purchase 100.00", "Tap or insert a test card", Expect(true), request = purchase("10000")),
        Case("PT-02", "Purchase max amount 9,999,999.99", "Tap or insert a test card", Expect(true), request = purchase("999999999")),
        Case("PT-03", "Purchase over max (10 digits)", "No card needed -- should be refused", Expect(false, listOf("format", "invalid", "amount")), request = purchase("1000000000")),
        Case("PT-05", "Reversal of PT-01 (OriTransId, no amount)", "No card needed", Expect(true), needs = listOf("PT-01")) { ref, prev ->
            val ori = prev["PT-01"]?.transId ?: return@Case null
            mapOf("TransType" to "Reversal", "TransIndexCode" to ref, "OriTransId" to ori)
        },
        Case("PT-06", "Refund 1.00 of PT-02 (OriTransId)", "Present the same card if asked", Expect(true), needs = listOf("PT-02")) { ref, prev ->
            val ori = prev["PT-02"]?.transId ?: return@Case null
            mapOf("TransType" to "Refund", "TransIndexCode" to ref, "TransAmount" to "100", "OriTransId" to ori)
        },
        Case("PT-17", "Purchase 90.01 -> Insufficient Funds", "Tap or insert a test card", Expect(false, listOf("insufficient", "funds")), request = purchase("9001")),
        Case("PT-18", "Purchase 90.02 -> Card Lost", "Tap or insert a test card", Expect(false, listOf("lost")), request = purchase("9002")),
        Case("PT-19", "Purchase 90.03 -> Card Expired", "Tap or insert a test card", Expect(false, listOf("expired")), request = purchase("9003")),
        Case("PT-20", "Purchase 90.04 -> Transaction Declined", "Tap or insert a test card", Expect(false, listOf("declin")), request = purchase("9004")),
        Case("PT-21", "Purchase 90.05 -> Transaction Timeout", "Tap or insert a test card", Expect(false, listOf("timeout", "time out", "timed out")), request = purchase("9005")),
        Case("PT-22", "Purchase 30.00 with TransIndexCode", "Tap or insert a test card", Expect(true), request = purchase("3000")),
        Case("PT-23", "QueryTransaction of PT-22", "No card needed", Expect(true), needs = listOf("PT-22")) { ref, prev ->
            val ori = prev["PT-22"]?.transIndexCode ?: return@Case null
            mapOf("TransType" to "QueryTransaction", "TransIndexCode" to ref, "OriTransIndexCode" to ori)
        },
        Case("A-07", "Purchase 1.30, let it time out", "Do NOT present a card -- wait for the timeout", Expect(false, listOf("cancel", "timeout")), request = purchase("130")),
        // EntryMode: 0x05 / 0x95 chip, 0x02 / 0x90 swipe (PAYWizard protocol V2.3.13).
        Case("A-09", "Purchase 2.00 by chip (insert)", "INSERT a chip card", Expect(true, entryModes = setOf(0x05, 0x95)), request = purchase("200")),
        Case("A-10", "Purchase 2.10 by magstripe (swipe)", "SWIPE a magnetic-stripe card", Expect(true, entryModes = setOf(0x02, 0x90)), request = purchase("210")),
        Case("SETTLE", "Settle (batch close)", "No card needed", Expect(true)) { ref, _ ->
            mapOf("TransType" to "Settle", "TransIndexCode" to ref)
        },
    )

    /** Pass / fail of [response] against [case]'s expectation. */
    fun evaluate(case: Case, request: Map<String, String>, response: String?): Result {
        if (response == null) return Result(case.id, Status.NO_RESPONSE, "PAYWizard did not answer", request, null)
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
            ?: return Result(case.id, Status.FAIL, "Answer is not JSON", request, response)
        val approved = root["TransResult"]?.jsonPrimitive?.booleanOrNull == true
        val desc = root["RespDesc"]?.jsonPrimitive?.content.orEmpty()
        val code = root["RespCode"]?.jsonPrimitive?.content.orEmpty()
        val entry = root["EntryMode"]?.jsonPrimitive?.content?.toIntOrNull()
        val problems = mutableListOf<String>()
        if (approved != case.expect.approved) problems += "TransResult=$approved, expected ${case.expect.approved}"
        if (case.expect.descHint.isNotEmpty() && case.expect.descHint.none { desc.contains(it, ignoreCase = true) }) {
            problems += "RespDesc \"$desc\" lacks ${case.expect.descHint.joinToString("/")}"
        }
        if (case.expect.entryModes.isNotEmpty() && entry !in case.expect.entryModes) problems += "EntryMode=$entry"
        val note = "RespCode=$code RespDesc=$desc" + (entry?.let { " EntryMode=$it" } ?: "")
        return Result(case.id, if (problems.isEmpty()) Status.PASS else Status.FAIL,
            if (problems.isEmpty()) note else problems.joinToString("; ") + " ($note)", request, response)
    }

    fun newRef(caseId: String) = "PTEST_" + caseId.replace("-", "") + "_" + System.currentTimeMillis()

    @Serializable
    private data class TestFlag(val payment_test_enabled: Boolean? = null)

    /** Only a terminal flagged in the cloud (devices.payment_test_enabled) may run the suite. */
    suspend fun isEnabled(sn: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            SupabaseClientProvider.client.postgrest["devices"].select { filter { eq("sn", sn) } }
                .decodeSingleOrNull<TestFlag>()?.payment_test_enabled == true
        }.getOrElse { Log.w(TAG, "Test flag check failed: ${it.message}"); false }
    }

    fun report(sn: String, appVersion: String, results: List<Result>): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        appendLine("GoldSky Aegis -- PAYWizard AIDL test report")
        appendLine("Terminal SN: $sn   App: $appVersion   Generated: ${fmt.format(Date())}")
        val any = results.firstOrNull { it.response != null }
        any?.let { appendLine("MID: ${it.field("MID") ?: "?"}   TID: ${it.field("TID") ?: "?"}   Merchant: ${it.field("MerchantName") ?: "?"}") }
        appendLine("Passed ${results.count { it.status == Status.PASS }} / ${results.size}")
        appendLine()
        cases.forEach { c ->
            val r = results.lastOrNull { it.caseId == c.id } ?: run { appendLine("${c.id}  ${c.title}: NOT RUN"); appendLine(); return@forEach }
            appendLine("${c.id}  ${c.title}: ${r.status}   ${fmt.format(Date(r.at))}")
            appendLine("  ${r.note}")
            listOf("TransType", "TransIndexCode", "TransAmount", "TransResult", "RespCode", "RespDesc", "TransID", "AuthCode", "RRN", "CardBrand", "EntryMode")
                .mapNotNull { k -> r.field(k)?.let { "$k=$it" } }.takeIf { it.isNotEmpty() }?.let { appendLine("  " + it.joinToString("  ")) }
            appendLine("  Request:  " + JsonObject(r.request.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            appendLine("  Response: " + (r.response ?: "(none)"))
            appendLine()
        }
    }

    /** Uploads the report next to the terminal's logs; returns the storage path. */
    suspend fun upload(sn: String, text: String): String? = withContext(Dispatchers.IO) {
        val path = "logs/${sn}_paytest_${System.currentTimeMillis()}.txt"
        runCatching { SupabaseClientProvider.client.storage["device-logs"].upload(path, text.toByteArray(), upsert = false); path }
            .getOrElse { Log.e(TAG, "Report upload failed: ${it.message}"); null }
    }
}
