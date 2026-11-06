package com.goldsky.ssp.payment

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The shared test simulator (Cael's Test simulator page, docs/migrations/2026-11-05_testsim.sql):
 * a terminal set to it sends what it would send to a real machine, and a person answers on the
 * page. Only the machine is simulated -- payments stay real. Every call returns null when the
 * server can't be reached, so the caller treats it as "no answer".
 */
object TestSimRepository {
    private const val TAG = "TestSim"

    @Serializable
    data class Status(
        val success: Boolean = false, val ready: Boolean = false, val message: String? = null,
        /** Slots run out of (2026-11-05_testsim_sold_out.sql). */
        val sold_out: List<String> = emptyList(),
    )

    @Serializable
    data class Answer(val success: Boolean = false, val answered: Boolean = false, val answer: JsonObject? = null, val message: String? = null)

    @Serializable
    private data class Submitted(val success: Boolean = false, val message: String? = null)

    @Serializable
    private data class SubmitParams(val p_kind: String, val p_ref: String, val p_payload: JsonObject, val p_deadline_ms: Long)

    @Serializable
    private data class AnswerParams(val p_kind: String, val p_ref: String)

    /** Is this terminal's simulated machine ready? */
    suspend fun status(): Status? = call("testsim_status") {
        SupabaseClientProvider.client.postgrest.rpc("testsim_status").decodeAs<Status>()
    }

    /** Sends a request ([ref] = the terminal's own id; sending it again changes nothing). */
    suspend fun submit(kind: String, ref: String, payload: JsonObject, deadlineMs: Long): Boolean =
        call("testsim_submit") {
            SupabaseClientProvider.client.postgrest.rpc("testsim_submit", SubmitParams(kind, ref, payload, deadlineMs))
                .decodeAs<Submitted>().success
        } == true

    /** The answer to [ref], once a person (or the deadline) has given one. */
    suspend fun answerOf(kind: String, ref: String): Answer? = call("testsim_answer_of") {
        SupabaseClientProvider.client.postgrest.rpc("testsim_answer_of", AnswerParams(kind, ref)).decodeAs<Answer>()
    }

    private suspend fun <T> call(name: String, block: suspend () -> T): T? = withContext(Dispatchers.IO) {
        try {
            SupabaseClientProvider.ensureAuthenticated()
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$name failed: ${e.message}")
            null
        }
    }
}
