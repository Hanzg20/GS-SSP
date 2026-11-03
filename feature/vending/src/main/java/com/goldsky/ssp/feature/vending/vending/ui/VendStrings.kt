package com.goldsky.ssp.vending.ui

import java.util.Locale

/**
 * Customer-facing text, English and French: machines in Ottawa-Gatineau serve
 * both, and on the Quebec side French has to be there. Plain wording, short
 * lines -- the Q3mini screen is about 4" and read at arm's length.
 */
class VendStrings(
    val langToggle: String,
    val startTitle: String,
    val startButton: String,
    val step1: String,
    val step2: String,
    val orChooseFirst: String,
    val authorizing: String,
    val keepCard: String,
    val approved: String,
    val chooseOnMachine: String,
    val holdNote: (String) -> String,
    val secondsLeft: (Int) -> String,
    val cancel: String,
    val item: (String) -> String,
    val tapToPay: String,
    val payButton: String,
    val dispensing: String,
    val takeItem: String,
    val charged: (String) -> String,
    val thanks: String,
    val notCharged: String,
    val noSelection: String,
    val cancelled: String,
    val vendFailed: String,
    val declined: String,
    val soldOut: String,
    val overLimit: (String) -> String,
    val unavailableTitle: String,
    val unavailableBody: String,
    val cardsAccepted: String,
    /** Big screen only: the customer pays on the terminal, not here. */
    val payAtTerminal: String,
) {
    /** Why nothing was charged ([limit] = the hold, for OVER_LIMIT). */
    fun reason(reason: com.goldsky.ssp.vending.logic.VendingState.Reason, limit: String): String = when (reason) {
        com.goldsky.ssp.vending.logic.VendingState.Reason.NO_SELECTION -> noSelection
        com.goldsky.ssp.vending.logic.VendingState.Reason.CANCELLED -> cancelled
        com.goldsky.ssp.vending.logic.VendingState.Reason.VEND_FAILED -> vendFailed
        com.goldsky.ssp.vending.logic.VendingState.Reason.DECLINED -> declined
        com.goldsky.ssp.vending.logic.VendingState.Reason.SOLD_OUT -> soldOut
        com.goldsky.ssp.vending.logic.VendingState.Reason.OVER_LIMIT -> overLimit(limit)
    }

    companion object {
        val EN = VendStrings(
            langToggle = "FRANÇAIS",
            startTitle = "Pay by card",
            startButton = "START",
            step1 = "Touch START and tap your card",
            step2 = "Choose your item on the machine",
            orChooseFirst = "Or choose on the machine first",
            authorizing = "Approving…",
            keepCard = "Keep your card on the reader",
            approved = "Approved",
            chooseOnMachine = "Now choose your item\non the machine",
            holdNote = { "A temporary hold up to $it may appear.\nYou pay only for what you get." },
            secondsLeft = { "$it s" },
            cancel = "CANCEL",
            item = { "Item $it" },
            tapToPay = "Tap, insert or swipe",
            payButton = "PAY",
            dispensing = "Dispensing…",
            takeItem = "Take your item",
            charged = { "Charged $it" },
            thanks = "Thank you",
            notCharged = "You were not charged",
            noSelection = "No item was chosen",
            cancelled = "Cancelled",
            vendFailed = "The item did not drop",
            declined = "Card declined — try another card",
            soldOut = "Sold out — choose another item",
            overLimit = { "Over the $it limit — choose another item" },
            unavailableTitle = "Card payment unavailable",
            unavailableBody = "This machine can't take cards right now.",
            cardsAccepted = "Interac · Visa · Mastercard · Amex · Apple/Google Pay",
            payAtTerminal = "Pay on the card terminal",
        )

        val FR = VendStrings(
            langToggle = "ENGLISH",
            startTitle = "Payer par carte",
            startButton = "DÉBUTER",
            step1 = "Touchez DÉBUTER, puis votre carte",
            step2 = "Choisissez l'article sur la machine",
            orChooseFirst = "Ou choisissez d'abord sur la machine",
            authorizing = "Autorisation…",
            keepCard = "Laissez votre carte sur le lecteur",
            approved = "Approuvé",
            chooseOnMachine = "Choisissez maintenant\nvotre article sur la machine",
            holdNote = { "Une retenue temporaire jusqu'à $it peut apparaître.\nVous ne payez que ce que vous recevez." },
            secondsLeft = { "$it s" },
            cancel = "ANNULER",
            item = { "Article $it" },
            tapToPay = "Sans contact, puce ou bande",
            payButton = "PAYER",
            dispensing = "Distribution…",
            takeItem = "Prenez votre article",
            charged = { "Montant payé : $it" },
            thanks = "Merci",
            notCharged = "Aucun montant n'a été débité",
            noSelection = "Aucun article choisi",
            cancelled = "Annulé",
            vendFailed = "L'article n'est pas tombé",
            declined = "Carte refusée — essayez une autre carte",
            soldOut = "Épuisé — choisissez un autre article",
            overLimit = { "Plus de $it — choisissez un autre article" },
            unavailableTitle = "Paiement par carte indisponible",
            unavailableBody = "Cette machine n'accepte pas les cartes pour le moment.",
            cardsAccepted = "Interac · Visa · Mastercard · Amex · Apple/Google Pay",
            payAtTerminal = "Payez au terminal de carte",
        )

        fun money(cents: Int, fr: Boolean): String =
            if (fr) String.format(Locale.CANADA_FRENCH, "%.2f $", cents / 100.0)
            else "$" + String.format(Locale.US, "%.2f", cents / 100.0)
    }
}
