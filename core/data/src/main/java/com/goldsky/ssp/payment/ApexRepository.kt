package com.goldsky.ssp.payment

import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.postgrest
import android.content.Context
import com.goldsky.ssp.core.data.R
import android.util.Log
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Apex (小海狸), the manager's assistant: a thin client of the apex-assistant
 * Edge Function. The terminal sends only the staff member, the question and
 * the language; the function works out the store from this device's session
 * and grounds every answer in that store's own numbers. Nothing here works
 * offline -- callers show [Result.Failed] and carry on.
 */
object ApexRepository {
    private const val TAG = "ApexRepository"
    private const val FUNCTION = "apex-assistant"
    private const val PREFS = "apex"
    private const val KEY_LANGUAGE = "language"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Request(
        val action: String,
        val staffId: String,
        val language: String,
        val timezone: String,
        val question: String? = null,
        val name: String? = null, // the mascot's name in this language (res apex_name)
        val refresh: Boolean = false,
        val before: String? = null,   // history: messages older than this (ISO)
        val limit: Int? = null,       // history: page size
        val text: String? = null      // remember: the fact to keep
    )

    // No default values here: kotlinx.serialization leaves defaults out of the
    // JSON, and a defaulted action = "transcribe" never reached the function
    // (it answered "Unknown action").
    @Serializable
    private data class TranscribeRequest(
        val action: String,
        val staffId: String,
        val language: String,
        val audio: String,
        val mime: String
    )

    // No defaults (see TranscribeRequest).
    @Serializable
    private data class ReceiptRequest(
        val action: String,
        val staffId: String,
        val language: String,
        val timezone: String,
        val text: String
    )

    data class Turn(val fromUser: Boolean, val text: String, val at: String)

    sealed class Result {
        /** [stats] is apex_store_stats()'s JSON (amounts in cents). */
        data class Brief(val text: String?, val stats: JsonObject?, val modelOff: Boolean) : Result()
        /** [sources]: what 小海狸 looked up for it ("销售 2026-11-01 – 2026-11-07"), shown under the answer. */
        data class Reply(val text: String, val sources: List<String> = emptyList()) : Result()
        /**
         * An expense or a reminder to confirm (apex-assistant's parse; amounts re-read
         * from the spoken words, dates range-checked). Keys: kind = "expense" (vendor,
         * category, amount [dollars, may be null], amountWords, spentOn, note) or
         * "reminder" (text, dueAt [ISO, may be null], whenWords); check = fields to look at.
         */
        data class Proposal(val data: JsonObject) : Result()
        /**
         * 小海狸 2.0: an answer with cards to confirm, one after another (expense, reminder,
         * delivery_order, todo_done, checklist_tick, note, stock_change, remember). [text] may
         * also answer a question asked in the same sentence.
         */
        data class Cards(val text: String, val cards: List<JsonObject>, val sources: List<String> = emptyList()) : Result()
        data class Transcript(val text: String) : Result()
        /** A receipt read by the model; every amount was found printed in the text (apex-assistant toReceipt). */
        data class Receipt(val vendor: String?, val date: String?, val totalCents: Int?, val taxCents: Int?, val hstNumber: String?) : Result()
        /** Oldest first; [Turn.at] is the server's ISO timestamp; [hasMore] = earlier messages to load. */
        data class History(val turns: List<Turn>, val hasMore: Boolean = false) : Result()
        data class Failed(val message: String) : Result()
    }

    /** The day's numbers plus the cached (hourly) brief; [refresh] forces a new brief. */
    // Every call takes the screen's (activity) context: its resources follow the
    // interface language (UiLanguage), and so does the language 小海狸 answers in.

    suspend fun brief(context: Context, staffId: String, refresh: Boolean = false): Result =
        call(context, Request("brief", staffId, UiLanguage.current(context), timezone(), name = context.getString(R.string.apex_name), refresh = refresh)) { body ->
            val stats = body["stats"] as? JsonObject
            if (body["skipped"] != null) Result.Brief(null, stats, modelOff = true)
            else Result.Brief(body["brief"]?.jsonPrimitive?.contentOrNull, stats, modelOff = false)
        }

    suspend fun ask(context: Context, staffId: String, question: String): Result =
        call(context, Request("ask", staffId, UiLanguage.current(context), timezone(), name = context.getString(R.string.apex_name), question = question)) { body ->
            when {
                body["skipped"] != null -> Result.Failed(
                    context.getString(R.string.apex_model_off)
                )
                (body["proposals"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true -> Result.Cards(
                    body["reply"]?.jsonPrimitive?.contentOrNull ?: "",
                    (body["proposals"] as kotlinx.serialization.json.JsonArray).mapNotNull { it as? JsonObject },
                    (body["sources"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull })
                body["proposal"] is JsonObject -> Result.Proposal(body["proposal"] as JsonObject)
                else -> body["reply"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    val sources = (body["sources"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull }
                    Result.Reply(text, sources)
                }
                    ?: Result.Failed("Empty reply")
            }
        }

    /**
     * A page of this staff member's chat with 小海狸, like a messenger: the newest [limit]
     * messages before [before] (null = the latest). The screen asks for more as they scroll up.
     */
    suspend fun history(context: Context, staffId: String, before: String? = null, limit: Int = 20): Result =
        call(context, Request("history", staffId, UiLanguage.current(context), timezone(), name = context.getString(R.string.apex_name),
            before = before, limit = limit)) { body ->
            val turns = (body["messages"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                val text = o["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Turn(o["role"]?.jsonPrimitive?.contentOrNull == "user", text, o["created_at"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            // An older function returns the last 50 with no has_more: then there is nothing more to page.
            Result.History(turns, body["has_more"]?.jsonPrimitive?.booleanOrNull ?: false)
        }

    /** One kind of thing worth a look (apex_alerts: low_stock, long_shift, delivery_out, offline_card, checklist_late, todo_overdue, loss_check, guest_missing, guest_diet). */
    @Serializable data class Alert(val kind: String, val count: Int = 0, val names: List<String> = emptyList())
    @Serializable private data class TzParams(val p_tz: String)

    /** The store's alerts now (fixed rules, no model); null = couldn't reach the server. */
    suspend fun alerts(): List<Alert>? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            SupabaseClientProvider.client.postgrest.rpc("apex_alerts", TzParams(timezone())).decodeAs<List<Alert>>()
        } catch (e: Exception) {
            Log.w(TAG, "alerts failed: ${e.message}"); null
        }
    }

    /** Keeps a lasting fact about the store the manager confirmed, for later answers. */
    suspend fun remember(context: Context, staffId: String, text: String): Boolean =
        call(context, Request("remember", staffId, UiLanguage.current(context), timezone(), text = text)) { body ->
            if (body["ok"]?.jsonPrimitive?.booleanOrNull == true) Result.Reply("ok") else Result.Failed("not saved")
        } is Result.Reply

    /** Speech to text for the hold-to-talk button; the caller shows the text for editing before sending. */
    suspend fun transcribe(context: Context, staffId: String, audio: java.io.File): Result {
        val language = UiLanguage.current(context)
        val bytes = withContext(Dispatchers.IO) { audio.readBytes() }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return call(context, TranscribeRequest("transcribe", staffId, language, b64, "audio/mp4"), "transcribe") { body ->
            when {
                body["skipped"] != null -> Result.Failed(
                    context.getString(R.string.apex_voice_off)
                )
                else -> body["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { Result.Transcript(it) }
                    ?: Result.Failed(context.getString(R.string.apex_didnt_catch))
            }
        }
    }

    /**
     * A receipt photo's text the terminal's own rules found no total in (ReceiptParser): the
     * model reads store, date, total, GST/HST and the HST number; amounts not printed in the
     * text come back null. Managers only, like everything 小海狸 does.
     */
    suspend fun readReceipt(context: Context, staffId: String, text: String): Result =
        call(context, ReceiptRequest("receipt", staffId, UiLanguage.current(context), timezone(), text.take(8000)), "receipt") { body ->
            val r = body["receipt"]?.let { runCatching { it.jsonObject }.getOrNull() }
            if (r == null) Result.Failed(body["skipped"]?.jsonPrimitive?.contentOrNull ?: "No receipt")
            else {
                fun s(k: String) = r[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                fun cents(k: String) = s(k)?.toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
                Result.Receipt(s("vendor"), s("date"), cents("total"), cents("tax"), s("hstNumber"))
            }
        }

    private fun timezone(): String = java.util.TimeZone.getDefault().id

    private suspend fun call(context: Context, request: Request, parse: (JsonObject) -> Result): Result =
        call(context, request, request.action, parse)

    private suspend fun call(context: Context, request: Any, action: String, parse: (JsonObject) -> Result): Result = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val response = SupabaseClientProvider.invokeFunction(FUNCTION, request)
            val text = response.bodyAsText()
            val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.status.isSuccess() || body == null) {
                val error = body?.get("error")?.jsonPrimitive?.contentOrNull ?: "HTTP ${response.status.value}"
                Log.w(TAG, "$action failed: $error")
                return@withContext Result.Failed(error)
            }
            parse(body)
        } catch (e: Exception) {
            Log.w(TAG, "$action failed: ${e.message}")
            Result.Failed(context.getString(R.string.apex_offline))
        }
    }
}
