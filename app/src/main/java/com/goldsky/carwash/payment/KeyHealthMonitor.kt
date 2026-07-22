package com.goldsky.carwash.payment

/**
 * Tracks PAX POSLink result codes for signs that DUKPT/PIN-pad key injection
 * is unhealthy, and gates further card-present transactions until a
 * technician resets it (key health is the gate on whether the device is
 * legally allowed to take card payments at all -- see docs/system_architecture.md
 * section 5.3). The exact PAX result codes for key-health failures must be
 * confirmed against the real POSLink integration guide before production;
 * these substrings are a reasonable starting point, not a verified spec,
 * since only local API stubs (not the real vendor SDK) are available here.
 */
object KeyHealthMonitor {
    private val KEY_FAILURE_MARKERS = listOf(
        "KEY", "DUKPT", "PIN PAD", "PINPAD", "PED FAIL"
    )
    private const val LOCK_AFTER_CONSECUTIVE_FAILURES = 2

    @Volatile private var consecutiveKeyFailures = 0
    @Volatile private var locked = false
    @Volatile private var lastReason: String? = null

    fun isPaymentAllowed(): Boolean = !locked

    fun lockReason(): String? = lastReason

    /**
     * Call after every POSLink ProcessTrans response, success or failure.
     */
    fun recordResult(resultCode: String?, resultMsg: String?) {
        val text = "${resultCode.orEmpty()} ${resultMsg.orEmpty()}".uppercase()
        val looksLikeKeyFailure = KEY_FAILURE_MARKERS.any { text.contains(it) }

        if (looksLikeKeyFailure) {
            consecutiveKeyFailures++
            lastReason = resultMsg ?: resultCode
            if (consecutiveKeyFailures >= LOCK_AFTER_CONSECUTIVE_FAILURES) {
                locked = true
            }
        } else {
            consecutiveKeyFailures = 0
        }
    }

    /**
     * Technician-only reset (e.g. after re-injecting keys), triggered from the
     * maintenance dashboard.
     */
    fun reset() {
        consecutiveKeyFailures = 0
        locked = false
        lastReason = null
    }
}
