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
    val notDispensed: String,
    val givenBackCard: String,
    val givenBackVip: String,
    val refundPending: String,
    val refundByStaff: String,
    val notPaid: String,
    val unavailableTitle: String,
    val unavailableBody: String,
    /** Top strip while no controller is connected: every sale is given back. */
    val noController: String,
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
            notDispensed = "NOT DISPENSED",
            givenBackCard = "Not charged",
            givenBackVip = "Returned to the VIP card",
            refundPending = "Goes back to the VIP card",
            refundByStaff = "Staff will refund",
            notPaid = "NOT PAID",
            unavailableTitle = "NOT AVAILABLE",
            unavailableBody = "Please try later",
            noController = "TEST · NOT CHARGED",
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
            notDispensed = "NON DISTRIBUÉ",
            givenBackCard = "Aucun débit",
            givenBackVip = "Rendu à la carte VIP",
            refundPending = "Retour automatique sur la carte VIP",
            refundByStaff = "Remboursement par le personnel",
            notPaid = "NON PAYÉ",
            unavailableTitle = "NON DISPONIBLE",
            unavailableBody = "Réessayez plus tard",
            noController = "TEST · AUCUN DÉBIT",
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
            sayNotDispensed = "Non distribué.",
            sayNotCharged = "Aucun débit.",
            sayNotPaid = "Non payé.",
        )

        fun money(cents: Int, fr: Boolean): String =
            if (fr) String.format(Locale.CANADA_FRENCH, "%.2f $", cents / 100.0)
            else "$" + String.format(Locale.US, "%.2f", cents / 100.0)
    }
}
