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

    sealed class Result {
        /** [stats] is apex_store_stats()'s JSON (amounts in cents). */
        data class Brief(val text: String?, val stats: JsonObject?, val modelOff: Boolean) : Result()
        data class Reply(val text: String) : Result()
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
                else -> body["reply"]?.jsonPrimitive?.contentOrNull?.let { Result.Reply(it) }
                    ?: Result.Failed("Empty reply")
            }
        }

    private fun timezone(): String = java.util.TimeZone.getDefault().id

    private suspend fun call(request: Request, parse: (JsonObject) -> Result): Result = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            val response = SupabaseClientProvider.invokeFunction(FUNCTION, request)
            val text = response.bodyAsText()
            val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.status.isSuccess() || body == null) {
                val error = body?.get("error")?.jsonPrimitive?.contentOrNull ?: "HTTP ${response.status.value}"
                Log.w(TAG, "${request.action} failed: $error")
                return@withContext Result.Failed(error)
            }
            parse(body)
        } catch (e: Exception) {
            Log.w(TAG, "${request.action} failed: ${e.message}")
            Result.Failed(if (request.language == "zh") "连不上服务器，请检查网络" else "Can't reach the server -- check the connection")
        }
    }
}
