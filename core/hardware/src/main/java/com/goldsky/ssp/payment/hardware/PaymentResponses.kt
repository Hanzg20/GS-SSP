package com.goldsky.ssp.payment.hardware

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The payment app's whole answer to an approved transaction, kept for reconciliation and refunds
 * (owner 2026-11-05: the bank's own transaction id was nowhere -- only PAYWizard's local TransID).
 * Providers hand each one to [listener] (core/data stores it on the transaction row) and to the
 * callback's onRawResponse, always through [sanitize] first.
 */
object PaymentResponses {
    /** (ecr ref of the original, TransType, sanitised answer). Set once by core/data. */
    @Volatile var listener: ((ref: String, type: String, answer: JsonObject) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** Keys never kept: track data, PIN, CVV, expiry, signature image. */
    private val SECRET = Regex("track|pin|cvv|cvc|expir|expdate|signature", RegexOption.IGNORE_CASE)

    /** Card numbers: first 6 and last 4 kept, the rest masked (already-masked ones stay as they are). */
    private val PAN_KEY = Regex("cardnum|pan|cardno", RegexOption.IGNORE_CASE)

    /** The answer as a JSON object without anything sensitive, or null if it isn't a JSON object. */
    fun sanitize(raw: String): JsonObject? = runCatching {
        val o = json.parseToJsonElement(raw).jsonObject
        JsonObject(o.filterKeys { !SECRET.containsMatchIn(it) }.mapValues { (k, v) ->
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (s != null && PAN_KEY.matches(k)) JsonPrimitive(maskPan(s)) else v
        })
    }.getOrNull()

    fun maskPan(s: String): String {
        val digits = s.count { it.isDigit() }
        if (digits < 12) return s
        var seen = 0
        return s.map { c ->
            if (!c.isDigit()) c else { seen++; if (seen <= 6 || seen > digits - 4) c else '*' }
        }.joinToString("")
    }
}
