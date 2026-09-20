package com.goldsky.ssp.payment

/**
 * Decides transactions.payment_method ("CREDIT_CARD" / "DEBIT_CARD") for a
 * card payment from what the terminal reported.
 *
 * Order: the terminal's own scheme flag when present, else the EMV AID of
 * debit-only applications. Visa/Mastercard debit cards on the standard
 * credit AIDs (e.g. Canadian Visa Debit) are indistinguishable by AID and
 * stay CREDIT_CARD until the terminal sends a scheme flag; card_aid/card_bin
 * are stored so those rows can be re-classified later.
 */
object CardTypeClassifier {
    private val DEBIT_AID_PREFIXES = listOf(
        "A0000002771010",   // Interac debit
        "A000000333010101", // UnionPay debit
        "A0000000043060",   // Maestro
        "A0000000032010"    // Visa Electron
    )

    fun paymentMethod(scheme: String?, aid: String?): String {
        when (scheme?.trim()?.lowercase()) {
            "debit" -> return "DEBIT_CARD"
            "credit" -> return "CREDIT_CARD"
        }
        val a = aid?.trim()?.uppercase()
        if (a != null && DEBIT_AID_PREFIXES.any { a.startsWith(it) }) return "DEBIT_CARD"
        return "CREDIT_CARD"
    }
}
