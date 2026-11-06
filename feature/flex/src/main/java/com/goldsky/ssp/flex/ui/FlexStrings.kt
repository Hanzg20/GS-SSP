package com.goldsky.ssp.flex.ui

import com.goldsky.ssp.flex.logic.SaleReason
import java.util.Locale

/**
 * Customer-facing text, English and French (Canada; Aegis Vend's words where they're the same).
 * Short, bold lines and no explanations -- the Q3mini screen is about 4" and read at arm's length.
 */
class FlexStrings(
    val choose: String,
    val pickOne: String,
    val pay: String,
    val tapCard: String,
    val scanTitle: String,
    val cancel: String,
    val dispensing: String,
    val takeItem: String,
    // Pre-authorization (owner 2026-11-05: the price is a hold, not what's charged): "UP TO" over a
    // tile's price, "HOLD $5.00" while paying, and what of the hold went back afterwards.
    val upTo: String,
    val hold: (String) -> String,
    val holdNote: String,
    val released: (String) -> String,
    // What was actually charged, said on the result screen (owner 2026-11-05).
    val paid: (String) -> String,
    val vipBalance: (String) -> String,
    val notDispensed: String,
    val givenBackCard: (String) -> String,
    val givenBackVip: (String) -> String,
    val refundPending: (String) -> String,
    val refundByStaff: (String) -> String,
    val notPaid: String,
    val unavailableTitle: String,
    val unavailableBody: String,
    /** Top strip while no controller is connected: every sale is given back. */
    val noController: String,
    /** Over a tile the controller has run out of. */
    val soldOut: String,
    // The idle (attract) screen: what to do, and how to pay.
    val touchToStart: String,
    val payByCard: String,
    val cancelled: String,
    val declined: String,
    val noCode: String,
    val vipInvalid: String,
    val vipLow: String,
    val vipOffline: String,
    // Voice: each main screen's words as a sentence, said three times (as Vend).
    val sayChoose: String,
    val sayTapCard: String,
    val sayScan: String,
    val sayDispensing: String,
    val sayTakeItem: String,
    val sayPaid: (String) -> String,
    val sayNotDispensed: String,
    val sayNotCharged: String,
    val sayNotPaid: String,
) {
    /** Why nothing was sold. */
    fun reason(reason: SaleReason): String = when (reason) {
        SaleReason.CANCELLED -> cancelled
        SaleReason.DECLINED -> declined
        SaleReason.NOT_DISPENSED -> notDispensed
        SaleReason.NO_CODE -> noCode
        SaleReason.VIP_INVALID -> vipInvalid
        SaleReason.VIP_LOW_BALANCE -> vipLow
        SaleReason.VIP_OFFLINE -> vipOffline
    }

    companion object {
        val EN = FlexStrings(
            choose = "SELECT",
            pickOne = "PICK ONE",
            pay = "PAY",
            tapCard = "TAP YOUR CARD",
            scanTitle = "SCAN YOUR VIP CODE",
            cancel = "CANCEL",
            dispensing = "DISPENSING…",
            takeItem = "TAKE YOUR ITEM",
            upTo = "UP TO",
            hold = { "HOLD $it" },
            holdNote = "CHARGED ONLY FOR WHAT YOU TAKE",
            released = { "$it HOLD RELEASED" },
            paid = { "PAID $it" },
            vipBalance = { "VIP BALANCE $it" },
            notDispensed = "NOT DISPENSED",
            givenBackCard = { "$it NOT CHARGED" },
            givenBackVip = { "$it BACK ON THE VIP CARD" },
            refundPending = { "$it GOES BACK TO THE VIP CARD" },
            refundByStaff = { "$it · STAFF WILL REFUND" },
            notPaid = "NOT PAID",
            unavailableTitle = "NOT AVAILABLE",
            unavailableBody = "Please try later",
            noController = "TEST · NOT CHARGED",
            soldOut = "SOLD OUT",
            touchToStart = "TOUCH TO START",
            payByCard = "TAP · INSERT · INTERAC",
            cancelled = "CANCELLED",
            declined = "CARD DECLINED",
            noCode = "NO CODE SCANNED",
            vipInvalid = "VIP CODE NOT VALID",
            vipLow = "LOW VIP BALANCE",
            vipOffline = "NETWORK ERROR",
            sayChoose = "Select, then touch pay.",
            sayTapCard = "Tap your card.",
            sayScan = "Scan your VIP code.",
            sayDispensing = "Dispensing.",
            sayTakeItem = "Take your item. Thank you.",
            sayPaid = { "Paid $it." },
            sayNotDispensed = "Not dispensed.",
            sayNotCharged = "Not charged.",
            sayNotPaid = "Not paid.",
        )

        val FR = FlexStrings(
            choose = "CHOISISSEZ",
            pickOne = "CHOISISSEZ",
            pay = "PAYER",
            tapCard = "PRÉSENTEZ VOTRE CARTE",
            scanTitle = "BALAYEZ VOTRE CODE VIP",
            cancel = "ANNULER",
            dispensing = "DISTRIBUTION…",
            takeItem = "PRENEZ VOTRE ARTICLE",
            upTo = "JUSQU'À",
            hold = { "RETENUE $it" },
            holdNote = "DÉBITÉ SEULEMENT POUR CE QUE VOUS PRENEZ",
            released = { "RETENUE DE $it LIBÉRÉE" },
            paid = { "PAYÉ $it" },
            vipBalance = { "SOLDE VIP $it" },
            notDispensed = "NON DISTRIBUÉ",
            givenBackCard = { "$it NON DÉBITÉ" },
            givenBackVip = { "$it RENDU À LA CARTE VIP" },
            refundPending = { "$it RETOURNE SUR LA CARTE VIP" },
            refundByStaff = { "$it · REMBOURSÉ PAR LE PERSONNEL" },
            notPaid = "NON PAYÉ",
            unavailableTitle = "NON DISPONIBLE",
            unavailableBody = "Réessayez plus tard",
            noController = "TEST · AUCUN DÉBIT",
            soldOut = "ÉPUISÉ",
            touchToStart = "TOUCHEZ POUR COMMENCER",
            payByCard = "SANS CONTACT · PUCE · INTERAC",
            cancelled = "ANNULÉ",
            declined = "CARTE REFUSÉE",
            noCode = "AUCUN CODE LU",
            vipInvalid = "CODE VIP INVALIDE",
            vipLow = "SOLDE VIP INSUFFISANT",
            vipOffline = "ERREUR DE RÉSEAU",
            sayChoose = "Choisissez, puis touchez payer.",
            sayTapCard = "Présentez votre carte.",
            sayScan = "Balayez votre code VIP.",
            sayDispensing = "Distribution.",
            sayTakeItem = "Prenez votre article. Merci.",
            sayPaid = { "Payé $it." },
            sayNotDispensed = "Non distribué.",
            sayNotCharged = "Aucun débit.",
            sayNotPaid = "Non payé.",
        )

        fun money(cents: Int, fr: Boolean): String =
            if (fr) String.format(Locale.CANADA_FRENCH, "%.2f $", cents / 100.0)
            else "$" + String.format(Locale.US, "%.2f", cents / 100.0)
    }
}
