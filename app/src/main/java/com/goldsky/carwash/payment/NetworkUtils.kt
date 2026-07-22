package com.goldsky.carwash.payment

import kotlinx.coroutines.delay

/**
 * Exponential-backoff retry for suspend network calls that talk to Supabase
 * directly via Ktor (repositories not backed by a WorkManager Worker, which
 * already gets backoff for free via Result.retry()). Retries on exception;
 * the final attempt's exception propagates to the caller.
 */
suspend fun <T> retryWithBackoff(
    times: Int = 3,
    initialDelayMs: Long = 500,
    maxDelayMs: Long = 8000,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelayMs
    repeat(times - 1) {
        try {
            return block()
        } catch (e: Exception) {
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
        }
    }
    return block()
}
