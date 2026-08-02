package com.goldsky.carwash.payment.hardware.idtech

/**
 * [IdTechPaymentProvider.startSale] arms EMV/CTLS/MSR concurrently, so up to
 * three independent SDK callback paths can each try to report the outcome of
 * the same physical attempt. This guard makes sure only the first one wins:
 * a customer inserting a chip and tapping at nearly the same instant, or a
 * mode's late/stray callback firing after cancellation, must not resolve the
 * same sale twice (double `onSuccess`/`onFailure`, e.g. two
 * `startFinalizationSequence()` calls for one payment).
 *
 * Kept as its own pure, reader-independent class (rather than a raw boolean
 * field on [IdTechPaymentProvider]) specifically so this exactly-once
 * behavior is unit testable without a real [com.idtechproducts.device.IDT_NEO2].
 */
internal class SaleResolutionGuard {
    private var resolved = false

    /** Call when arming a new attempt so a previous attempt's guard doesn't leak into it. */
    fun reset() {
        resolved = false
    }

    /**
     * Runs [action] only if nothing has resolved this attempt yet. Returns
     * whether [action] ran, so callers can decide whether to do associated
     * work (e.g. cancelling the modes that didn't win).
     */
    fun resolve(action: () -> Unit): Boolean {
        if (resolved) return false
        resolved = true
        action()
        return true
    }
}
