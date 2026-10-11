package com.goldsky.ssp.payment

import android.content.Context
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
        val refresh: Boolean = false
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

    data class Turn(val fromUser: Boolean, val text: String, val at: String)

    sealed class Result {
        /** [stats] is apex_store_stats()'s JSON (amounts in cents). */
        data class Brief(val text: String?, val stats: JsonObject?, val modelOff: Boolean) : Result()
        data class Reply(val text: String) : Result()
        /**
         * An expense or a reminder to confirm (apex-assistant's parse; amounts re-read
         * from the spoken words, dates range-checked). Keys: kind = "expense" (vendor,
         * category, amount [dollars, may be null], amountWords, spentOn, note) or
         * "reminder" (text, dueAt [ISO, may be null], whenWords); check = fields to look at.
         */
        data class Proposal(val data: JsonObject) : Result()
        data class Transcript(val text: String) : Result()
        /** Oldest first; [Turn.at] is the server's ISO timestamp. */
        data class History(val turns: List<Turn>) : Result()
        data class Failed(val message: String) : Result()
    }

    /** "zh" or "en", remembered per terminal; Chinese first for the stores we serve. */
    fun language(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "zh") ?: "zh"

    fun setLanguage(context: Context, language: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, language).apply()

    /** The day's numbers plus the cached (hourly) brief; [refresh] forces a new brief. */
    suspend fun brief(staffId: String, language: String, refresh: Boolean = false): Result =
        call(Request("brief", staffId, language, timezone(), refresh = refresh)) { body ->
            val stats = body["stats"] as? JsonObject
            if (body["skipped"] != null) Result.Brief(null, stats, modelOff = true)
            else Result.Brief(body["brief"]?.jsonPrimitive?.contentOrNull, stats, modelOff = false)
        }

    suspend fun ask(staffId: String, language: String, question: String): Result =
        call(Request("ask", staffId, language, timezone(), question = question)) { body ->
            when {
                body["skipped"] != null -> Result.Failed(
                    if (language == "zh") "小海狸还没开通（云端未配置模型）" else "Beaver isn't switched on yet (no model configured)"
                )
                body["proposal"] is JsonObject -> Result.Proposal(body["proposal"] as JsonObject)
                else -> body["reply"]?.jsonPrimitive?.contentOrNull?.let { Result.Reply(it) }
                    ?: Result.Failed("Empty reply")
            }
        }

    /** This staff member's earlier chat with 小海狸 (last 50 messages), to show on reopening. */
    suspend fun history(staffId: String, language: String): Result =
        call(Request("history", staffId, language, timezone())) { body ->
            val turns = (body["messages"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                val text = o["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Turn(o["role"]?.jsonPrimitive?.contentOrNull == "user", text, o["created_at"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            Result.History(turns)
        }

    /** Speech to text for the hold-to-talk button; the caller shows the text for editing before sending. */
    suspend fun transcribe(staffId: String, language: String, audio: java.io.File): Result {
        val bytes = withContext(Dispatchers.IO) { audio.readBytes() }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return call(TranscribeRequest("transcribe", staffId, language, b64, "audio/mp4"), language, "transcribe") { body ->
            when {
                body["skipped"] != null -> Result.Failed(
                    if (language == "zh") "语音输入还没开通（云端未配置识别服务）" else "Voice input isn't switched on yet (no speech service configured)"
                )
                else -> body["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { Result.Transcript(it) }
                    ?: Result.Failed(if (language == "zh") "没听清，请再说一次" else "Didn't catch that -- try again")
            }
        }
    }

    private fun timezone(): String = java.util.TimeZone.getDefault().id

    private suspend fun call(request: Request, parse: (JsonObject) -> Result): Result =
        call(request, request.language, request.action, parse)

    private suspend fun call(request: Any, language: String, action: String, parse: (JsonObject) -> Result): Result = withContext(Dispatchers.IO) {
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
            Result.Failed(if (language == "zh") "连不上服务器，请检查网络" else "Can't reach the server -- check the connection")
        }
    }
}
