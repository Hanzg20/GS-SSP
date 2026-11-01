package com.goldsky.ssp.payment.wizarpos

import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads PAYWizard's answer to a "Deferred" upload. The protocol (V2.3.13) only says
 * DeferredSummaryInfo holds "the number of successful and failed transactions, as
 * well as the relevant details for each transaction" -- not the field names. So this
 * reads it leniently and never guesses a sale was sent:
 *  - a sale object = any object with a TransIndexCode (our ref) and a result
 *    (TransResult / Result / Status / Success, or a RespCode "00");
 *  - the counts = integer fields whose name says fail(ed) / success.
 * [IPaymentProvider.OfflineUpload.Done.allSent] only when TransResult is true and a
 * failure count is present and 0. A sale it can't classify stays pending.
 */
object OfflineUploadParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): IPaymentProvider.OfflineUpload {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return IPaymentProvider.OfflineUpload.Failed("Unreadable answer")
        val ok = root["TransResult"]?.prim()?.toBooleanStrictOrNull()
        val uploaded = mutableListOf<String>()
        val declined = mutableListOf<String>()
        var failCount: Int? = null
        fun walk(e: JsonElement) {
            when (e) {
                is JsonArray -> e.forEach(::walk)
                is JsonObject -> {
                    val ref = e["TransIndexCode"]?.prim()?.takeIf { it.isNotBlank() }
                    if (ref != null) when (resultOf(e)) {
                        true -> uploaded += ref
                        false -> declined += ref
                        null -> {}
                    }
                    e.forEach { (k, v) ->
                        val n = (v as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
                        if (n != null && Regex("fail", RegexOption.IGNORE_CASE).containsMatchIn(k)) failCount = (failCount ?: 0) + n
                        if (v is JsonObject || v is JsonArray) walk(v)
                    }
                }
                else -> {}
            }
        }
        walk(root)
        if (ok == false && uploaded.isEmpty() && declined.isEmpty()) {
            return IPaymentProvider.OfflineUpload.Failed(root["RespDesc"]?.prim() ?: "Upload failed")
        }
        return IPaymentProvider.OfflineUpload.Done(uploaded.distinct(), declined.distinct(),
            allSent = ok == true && failCount == 0 && declined.isEmpty(), raw = raw)
    }

    private fun JsonElement.prim(): String? = (this as? JsonPrimitive)?.content?.trim()?.takeIf { it != "null" }

    /** true = sent and accepted, false = refused, null = can't tell. */
    private fun resultOf(o: JsonObject): Boolean? {
        for (k in listOf("TransResult", "Result", "Status", "Success", "UploadResult")) {
            val v = o[k]?.prim()?.lowercase() ?: continue
            when (v) {
                "true", "success", "succeeded", "ok", "approved", "uploaded", "0", "00" -> return true
                "false", "fail", "failed", "failure", "declined", "rejected", "1" -> return false
            }
        }
        val code = o["RespCode"]?.prim() ?: return null
        return code == "00" || code == "0"
    }
}
