package com.goldsky.ssp.payment.hardware.idtech

import com.idtechproducts.device.IDTEMVData

/**
 * What an [IDTEMVData.result] code means for the sale in progress. Kept
 * separate from [IdTechPaymentProvider] (which decides *what to do* about
 * each category -- log text, whether to call emv_completeTransaction, the
 * exact onFailure message) so the code-to-category mapping itself -- the
 * part most likely to regress if the SDK's constants are ever misread again
 * -- is a pure function unit tests can exercise without a real reader.
 */
internal enum class EmvResultCategory {
    /** Kernel handshake still in progress; not a final outcome. */
    CONTINUE,

    /** Local processing done, reader wants an online host authorization. */
    ONLINE_AUTH_REQUIRED,
    APPROVED,
    DECLINED,
    CANCELLED,
    TIMEOUT,

    /** Chip read failed; MSR is armed concurrently so a swipe can still work. */
    FALLBACK,

    /** CTLS-specific "didn't read cleanly, try again" outcomes. */
    CTLS_RETRY,

    /** Card requires signature/online-PIN capture, which isn't implemented. */
    VERIFICATION_UNSUPPORTED,

    /** Anything not explicitly mapped above. */
    UNKNOWN,
}

/**
 * Maps the SDK's own named `int` constants (verified via `javap` against the
 * vendored jar) to a category -- not guessed magic numbers.
 */
internal fun classifyEmvResult(result: Int): EmvResultCategory = when (result) {
    IDTEMVData.START_TRANS_SUCCESS -> EmvResultCategory.CONTINUE
    IDTEMVData.GO_ONLINE, IDTEMVData.GO_ONLINE_CTLS -> EmvResultCategory.ONLINE_AUTH_REQUIRED
    IDTEMVData.APPROVED, IDTEMVData.APPROVED_OFFLINE -> EmvResultCategory.APPROVED
    IDTEMVData.DECLINED, IDTEMVData.DECLINED_OFFLINE, IDTEMVData.NOT_ACCEPTED,
    IDTEMVData.CARD_REJECTED, IDTEMVData.CARD_BLOCKED, IDTEMVData.CALL_YOUR_BANK -> EmvResultCategory.DECLINED
    IDTEMVData.TRANSACTION_CANCELED -> EmvResultCategory.CANCELLED
    IDTEMVData.TIME_OUT, IDTEMVData.PIN_ENTRY_TIMEOUT -> EmvResultCategory.TIMEOUT
    IDTEMVData.USE_MAGSTRIPE, IDTEMVData.FALLBACK_TO_CONTACT,
    IDTEMVData.FALLBACK_TO_OTHER, IDTEMVData.FALLBACK_SITUATION -> EmvResultCategory.FALLBACK
    IDTEMVData.CTLS_TWO_CARDS, IDTEMVData.CTLS_TERMINATE,
    IDTEMVData.CTLS_TERMINATE_TRY_ANOTHER -> EmvResultCategory.CTLS_RETRY
    IDTEMVData.REQUEST_SIGNATURE, IDTEMVData.REQUEST_ONLINE_PIN -> EmvResultCategory.VERIFICATION_UNSUPPORTED
    else -> EmvResultCategory.UNKNOWN
}
