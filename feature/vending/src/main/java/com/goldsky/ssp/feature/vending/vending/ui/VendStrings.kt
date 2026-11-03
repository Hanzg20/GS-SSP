package com.goldsky.ssp.vending.ui

import java.util.Locale

/**
 * Customer-facing text, English and French: machines in Ottawa-Gatineau serve
 * both, and on the Quebec side French has to be there. Short, bold lines and no
 * explanations (owner, 2026-11-03) -- the Q3mini screen is about 4" and read at arm's length.
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
    // Item first only (PurchaseFlow.ITEM_FIRST): no START, the price comes from the machine.
    val itemFirstTitle: String,
    val itemStep2: String,
    val priceShowsHere: String,
    // Card first only (PurchaseFlow.CARD_FIRST): chosen on the machine without a card.
    val startFirst: String,
    /** The idle screen's VIP banner (opens Wash's VipActivity): plain, or with the best bonus %. */
    val vipBanner: String,
    val vipBonus: (Int) -> String,
) {
    /** Why nothing was charged ([limit] = the hold, for OVER_LIMIT). */
    fun reason(reason: com.goldsky.ssp.vending.logic.VendingState.Reason, limit: String): String = when (reason) {
        com.goldsky.ssp.vending.logic.VendingState.Reason.NO_SELECTION -> noSelection
        com.goldsky.ssp.vending.logic.VendingState.Reason.CANCELLED -> cancelled
        com.goldsky.ssp.vending.logic.VendingState.Reason.VEND_FAILED -> vendFailed
        com.goldsky.ssp.vending.logic.VendingState.Reason.DECLINED -> declined
        com.goldsky.ssp.vending.logic.VendingState.Reason.SOLD_OUT -> soldOut
        com.goldsky.ssp.vending.logic.VendingState.Reason.OVER_LIMIT -> overLimit(limit)
        com.goldsky.ssp.vending.logic.VendingState.Reason.START_FIRST -> startFirst
    }

    companion object {
        val EN = VendStrings(
            langToggle = "FRANÇAIS",
            startTitle = "PAY BY CARD",
            startButton = "START",
            step1 = "START, then tap your card",
            step2 = "Choose on the machine",
            orChooseFirst = "or choose on the machine",
            authorizing = "APPROVING…",
            keepCard = "Keep your card on the reader",
            approved = "APPROVED",
            chooseOnMachine = "CHOOSE YOUR ITEM",
            holdNote = { "Hold up to $it" },
            secondsLeft = { "$it" },
            cancel = "CANCEL",
            item = { "ITEM $it" },
            tapToPay = "TAP YOUR CARD",
            payButton = "PAY",
            dispensing = "DISPENSING…",
            takeItem = "TAKE YOUR ITEM",
            charged = { "PAID $it" },
            thanks = "THANK YOU",
            notCharged = "NOT CHARGED",
            noSelection = "NO ITEM CHOSEN",
            cancelled = "CANCELLED",
            vendFailed = "ITEM DID NOT DROP",
            declined = "CARD DECLINED",
            soldOut = "SOLD OUT",
            overLimit = { "OVER $it" },
            unavailableTitle = "CARD PAYMENT UNAVAILABLE",
            unavailableBody = "Please try later",
            cardsAccepted = "Interac · Visa · Mastercard · Amex · Apple/Google Pay",
            payAtTerminal = "PAY ON THE TERMINAL",
            vipBanner = "★ BECOME A VIP",
            vipBonus = { "★ VIP · UP TO $it% BONUS" },
            itemFirstTitle = "CHOOSE ON THE MACHINE",
            itemStep2 = "Then pay here",
            priceShowsHere = "Price shows here",
            startFirst = "TOUCH START FIRST",
        )

        val FR = VendStrings(
            langToggle = "ENGLISH",
            startTitle = "PAYER PAR CARTE",
            startButton = "DÉBUTER",
            step1 = "DÉBUTER, puis votre carte",
            step2 = "Choisissez sur la machine",
            orChooseFirst = "ou choisissez sur la machine",
            authorizing = "AUTORISATION…",
            keepCard = "Laissez la carte sur le lecteur",
            approved = "APPROUVÉ",
            chooseOnMachine = "CHOISISSEZ VOTRE ARTICLE",
            holdNote = { "Retenue jusqu'à $it" },
            secondsLeft = { "$it" },
            cancel = "ANNULER",
            item = { "ARTICLE $it" },
            tapToPay = "PRÉSENTEZ VOTRE CARTE",
            payButton = "PAYER",
            dispensing = "DISTRIBUTION…",
            takeItem = "PRENEZ VOTRE ARTICLE",
            charged = { "PAYÉ $it" },
            thanks = "MERCI",
            notCharged = "AUCUN DÉBIT",
            noSelection = "AUCUN ARTICLE CHOISI",
            cancelled = "ANNULÉ",
            vendFailed = "ARTICLE NON DISTRIBUÉ",
            declined = "CARTE REFUSÉE",
            soldOut = "ÉPUISÉ",
            overLimit = { "PLUS DE $it" },
            unavailableTitle = "PAIEMENT PAR CARTE INDISPONIBLE",
            unavailableBody = "Réessayez plus tard",
            cardsAccepted = "Interac · Visa · Mastercard · Amex · Apple/Google Pay",
            payAtTerminal = "PAYEZ AU TERMINAL",
            vipBanner = "★ DEVENEZ VIP",
            vipBonus = { "★ VIP · JUSQU'À $it % EN PRIME" },
            itemFirstTitle = "CHOISISSEZ SUR LA MACHINE",
            itemStep2 = "Puis payez ici",
            priceShowsHere = "Le prix s'affiche ici",
            startFirst = "TOUCHEZ D'ABORD DÉBUTER",
        )

        fun money(cents: Int, fr: Boolean): String =
            if (fr) String.format(Locale.CANADA_FRENCH, "%.2f $", cents / 100.0)
            else "$" + String.format(Locale.US, "%.2f", cents / 100.0)
    }
}
