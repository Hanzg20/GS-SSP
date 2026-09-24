package com.goldsky.ssp.payment.hardware

/**
 * Card brand and entry-mode normalisation for what terminals report, so
 * transactions.card_brand / entry_mode are filled and comparable across
 * vendors. Both were always empty in the cloud before (2026-09-24).
 */
object CardBrands {

    // EMV RID (first 10 hex digits of the AID) -> brand.
    private val RID_BRANDS = mapOf(
        "A000000003" to "VISA",
        "A000000004" to "MASTERCARD",
        "A000000025" to "AMEX",
        "A000000152" to "DISCOVER",
        "A000000277" to "INTERAC",
        "A000000065" to "JCB",
        "A000000333" to "UNIONPAY",
    )

    /**
     * The terminal's own brand when it sent one, else derived from the AID.
     * The WizarPOS emulator leaves CardBrand empty on purchases (it fills it
     * on reversals), and a real host may too -- the AID is always there for
     * chip/contactless cards.
     */
    fun brand(reported: String?, aid: String?): String? =
        reported?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.uppercase()
            ?: aid?.trim()?.uppercase()?.takeIf { it.length >= 10 }?.let { RID_BRANDS[it.take(10)] }

    /**
     * WizarPOS PAYWizard EntryMode (Integration Protocol V2.3.13): 0x01
     * manual, 0x02 swipe, 0x05 chip, 0x07 contactless, 0x90 swipe (CVV),
     * 0x91 contactless MSD, 0x95 chip (no CVV), 0x99 stored-value. Arrives
     * as a JSON number (decimal) or occasionally a "0x.." string.
     */
    fun wizarPosEntryMode(raw: String?): String {
        val v = raw?.trim().orEmpty()
        val code = when {
            v.startsWith("0x", ignoreCase = true) -> v.drop(2).toIntOrNull(16)
            else -> v.toIntOrNull()
        }
        return when (code) {
            0x01 -> "MANUAL"
            0x02, 0x90 -> "SWIPE"
            0x05, 0x95 -> "CHIP"
            0x07 -> "CONTACTLESS"
            0x91 -> "CONTACTLESS_MSD"
            0x99 -> "STORED_VALUE"
            null -> "UNKNOWN"
            else -> "UNKNOWN_$code"
        }
    }
}
