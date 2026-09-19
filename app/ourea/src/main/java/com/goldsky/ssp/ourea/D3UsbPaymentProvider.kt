package com.goldsky.ssp.ourea

import android.content.Context
import com.goldsky.ssp.payment.hardware.IPaymentProvider

/**
 * SCAFFOLD ONLY, NOT IMPLEMENTED (2026-08-27) -- see docs/system_architecture.md
 * v2.37 for the full decision trail before touching this file.
 *
 * Confirmed architecture: unlike every other vendor integration in this
 * codebase (PAX/ID TECH/WizarPOS Q3mini -- all "app runs on the same
 * terminal as the payment hardware, calls its SDK/local-socket in-process"),
 * this is Ourea (a bigger-screen countertop host) acting as **USB host**
 * to an **externally-attached, physically separate WizarPOS D3 "Smart ECR"**
 * card reader. That's a genuinely different integration surface -- USB
 * device enumeration/permission handling, connection lifecycle, and wire
 * protocol are all unlike the existing WizarPosPaymentProvider (which talks
 * to a co-located PAYWizard app over 127.0.0.1:6666, see
 * docs/wizarpos_upt_integration_spec.md) -- so this is deliberately its own
 * class, not a variant of that one.
 *
 * NOT confirmed, and NOT guessed at here: the actual USB wire protocol.
 * A secondhand summary (not the original PDF/spec text) claimed D3 uses a
 * "PAYWizard AIDL interface" in "Default" connection mode (vs. Q3's "USB
 * Accessory" mode) with payment package `com.wizarpos.opc`, but this
 * directly contradicts docs/wizarpos_upt_integration_spec.md (built from
 * WizarPOS's own protocol PDF, real-hardware-verified for Q3mini), which
 * describes a plain local socket with no AIDL or USB-accessory-mode
 * involved. That contradiction was never resolved with a primary source, so
 * every method below fails loudly instead of guessing at a message format
 * -- inventing SALE/VOID/REFUND wire framing for a live payment terminal is
 * exactly the kind of mistake that either doesn't work or, worse, silently
 * does the wrong thing with real money.
 *
 * To finish this: get the real WizarPOS D3/D22 USB integration spec (the
 * equivalent of PAYWizard/WIZARPOSPaymentAppIntegrationProtocolV2.3.13.pdf
 * that Q3mini's integration was built from) -- specifically: USB host vs.
 * accessory mode, vendor/product ID, transfer type (bulk/interrupt/control),
 * message framing, and the actual SALE/VOID/REFUND payload shapes. Only
 * then fill in the TODOs below; don't infer them from Q3mini's socket
 * protocol, they're not the same transport.
 */
class D3UsbPaymentProvider(private val context: Context) : IPaymentProvider {

    private fun notImplemented(callback: IPaymentProvider.PaymentCallback) {
        callback.onFailure(
            "D3 USB integration not implemented -- pending real protocol spec, see class doc",
            isHardwareFault = true
        )
    }

    override fun startSale(amountInCents: Int, ecrRefNum: String, callback: IPaymentProvider.PaymentCallback) {
        // TODO: enumerate UsbManager.deviceList, match D3's vendor/product ID,
        // request UsbManager.requestPermission if not yet granted, open the
        // device/interface, and send a SALE message in D3's real wire format.
        notImplemented(callback)
    }

    override fun voidTransaction(refNum: String, callback: IPaymentProvider.PaymentCallback) {
        notImplemented(callback)
    }

    override fun refundTransaction(refNum: String, amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        notImplemented(callback)
    }

    override fun startCardDetection(amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        notImplemented(callback)
    }

    override fun stopCardDetection() {
        // No-op until a real USB session exists to cancel.
    }

    override fun cancelCurrentTransaction() {
        // No-op until a real USB session exists to cancel.
    }

    override fun closeBatch(callback: IPaymentProvider.PaymentCallback) {
        notImplemented(callback)
    }
}
