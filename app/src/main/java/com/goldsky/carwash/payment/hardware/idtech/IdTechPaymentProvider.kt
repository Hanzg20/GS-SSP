package com.goldsky.carwash.payment.hardware.idtech

import android.util.Log
import com.goldsky.carwash.payment.hardware.IPaymentProvider
import com.idtechproducts.device.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ID TECH implementation of IPaymentProvider.
 * Converts NEO2/NEO3 SDK callbacks into unified app-level payment states.
 *
 * This class IS the [OnReceiverListener] that must be passed into the [IDT_NEO2]
 * constructor -- it cannot be constructed with the reader up front because the
 * reader itself requires a listener at construction time. [IdTechHardwareProvider]
 * creates this first, then builds the reader with it as the listener, then calls
 * [attachReader]. Do not reintroduce a separate dummy listener for the reader;
 * doing so silently disconnects every callback below from the SDK.
 */
class IdTechPaymentProvider : IPaymentProvider, OnReceiverListener {
    private val TAG = "IdTechPayment"
    private var currentCallback: IPaymentProvider.PaymentCallback? = null
    private var reader: IDT_NEO2? = null

    /**
     * Guards against a second card-presentment mode's result arriving after
     * the sale has already been resolved by another (e.g. customer inserts
     * a chip AND taps roughly at once, or a mode's late/stray callback fires
     * after cancellation). See [SaleResolutionGuard] for why this is its own
     * class rather than a raw boolean field.
     */
    private val saleGuard = SaleResolutionGuard()

    fun attachReader(reader: IDT_NEO2) {
        this.reader = reader
    }

    fun detachReader() {
        this.reader = null
        this.currentCallback = null
        this.saleGuard.reset()
    }

    /**
     * Arms ALL THREE card-presentment modes (contact insert, CTLS tap, MSR
     * swipe) concurrently for the given amount -- whichever the customer
     * actually uses resolves the sale via [finishSale]. This replaces an
     * earlier two-stage design (a separate "detection" step before "the real
     * sale") that doesn't hold for ID TECH: unlike PAX, there's no cheap
     * presence-only check independent of actually running the transaction --
     * a completed MSR swipe or CTLS tap IS already a final APPROVED/DECLINED
     * result, so starting a second, separate emv_startTransaction() afterward
     * (as the old two-stage flow did) would mean either a duplicate auth
     * attempt or a 30s hang waiting for a chip insert that isn't coming.
     * [startCardDetection] is kept only to satisfy [IPaymentProvider] and
     * does no hardware work of its own -- see its doc.
     */
    override fun startSale(amountInCents: Int, ecrRefNum: String, callback: IPaymentProvider.PaymentCallback) {
        val reader = this.reader ?: return callback.onFailure("Reader not initialized", isHardwareFault = true)
        currentCallback = callback
        saleGuard.reset()
        Log.i(TAG, "Starting ID TECH sale: $amountInCents cents (contact+CTLS+MSR armed concurrently)")

        // ID TECH uses double for amount (e.g. 1.00)
        val amount = amountInCents / 100.0

        // Not verified against vendor docs that arming all three concurrently
        // is safe/supported on this reader -- revisit if real-hardware testing
        // shows interference between them.
        val emvRet = reader.emv_startTransaction(amount, 0.0, 0, 30, null, false)
        if (emvRet != ErrorCode.SUCCESS) {
            Log.w(TAG, "Failed to start EMV: " + reader.device_getResponseCodeString(emvRet))
        }
        val ctlsRet = reader.ctls_startTransaction(amount, 0.0, 0, 30, null)
        if (ctlsRet != ErrorCode.SUCCESS) {
            Log.w(TAG, "Failed to start CTLS: " + reader.device_getResponseCodeString(ctlsRet))
        }
        val msrRet = reader.msr_startMSRSwipe()
        if (msrRet != ErrorCode.SUCCESS) {
            Log.w(TAG, "Failed to start MSR: " + reader.device_getResponseCodeString(msrRet))
        }

        if (emvRet != ErrorCode.SUCCESS && ctlsRet != ErrorCode.SUCCESS && msrRet != ErrorCode.SUCCESS) {
            callback.onFailure("Failed to start card acceptance (EMV+CTLS+MSR)", isHardwareFault = true)
        }
    }

    override fun voidTransaction(refNum: String, callback: IPaymentProvider.PaymentCallback) {
        callback.onFailure("VOID not yet implemented for ID TECH")
    }

    override fun refundTransaction(refNum: String, amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        callback.onFailure("REFUND not yet implemented for ID TECH")
    }

    /**
     * No-op pass-through: ID TECH has no cheap presence-only check that's
     * separate from actually running the transaction (see [startSale]), so
     * there is nothing useful to do here. Immediately reports success so
     * MainActivity's existing detect-then-sale call sequence proceeds
     * straight to startSale(), which is where card acceptance actually
     * starts. [amountInCents] is intentionally unused.
     */
    override fun startCardDetection(amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        callback.onSuccess("", "")
    }

    override fun stopCardDetection() {
        // No-op: startCardDetection() no longer arms any hardware listening
        // (see its doc). Real cancellation is cancelCurrentTransaction().
    }

    /**
     * Stops whichever of the three concurrently-armed modes (see [startSale])
     * didn't already resolve the sale, once a winner is known.
     */
    private fun finishSale(action: () -> Unit) {
        saleGuard.resolve {
            reader?.msr_cancelMSRSwipe()
            reader?.ctls_cancelTransaction()
            action()
        }
    }

    override fun cancelCurrentTransaction() {
        Log.w(TAG, "User requested transaction cancel")
        saleGuard.resolve {}
        val reader = this.reader ?: return
        reader.emv_cancelTransaction(ResDataStruct())
        reader.msr_cancelMSRSwipe()
        reader.ctls_cancelTransaction()
        // Explicitly drop the backlight so a cancelled tap doesn't leave the
        // reader lit and the USB handle held open (spec §1.2). Second param
        // is an SDK-defined target byte; unverified against vendor docs, 0
        // is the common "default/primary" value in this SDK family.
        reader.lcd_setBacklight(false, 0)
        currentCallback = null
    }

    override fun closeBatch(callback: IPaymentProvider.PaymentCallback) {
        Log.i(TAG, "MOCK ID TECH BATCH CLOSE: Settle Successful")
        callback.onSuccess("MOCK_BATCH_OK", "")
    }

    // --- ID TECH OnReceiverListener Implementation ---

    /**
     * [IDTEMVData.result] is routed through [classifyEmvResult] (see that
     * file for why it's a separate pure function) rather than switched on
     * inline -- these are the actual values the kernel returns for both
     * contact EMV and CTLS (CTLS shares this callback and result struct; the
     * CTLS-specific codes like [IDTEMVData.GO_ONLINE_CTLS] land here too,
     * *not* in [ctlsEvent], which only carries raw framing/detection events).
     * IDTEMVData.MSR_SUCCESS is deliberately not treated as a final outcome
     * here: MSR results are owned by [swipeMSRData] below, and firing
     * onSuccess from both callbacks for the same physical swipe would
     * double-submit the same sale.
     */
    override fun emvTransactionData(emvData: IDTEMVData) {
        val statusMsg = Common.emvErrorCodes(emvData.result)
        Log.i(TAG, "EMV Result: $statusMsg (${emvData.result})")

        when (classifyEmvResult(emvData.result)) {
            EmvResultCategory.CONTINUE -> {
                Log.i(TAG, "EMV: Kernel start success")
                reader?.emv_authenticateTransaction(null)
            }
            EmvResultCategory.ONLINE_AUTH_REQUIRED -> {
                // The reader has done local EMV/CTLS processing and is asking THIS
                // APP to relay the transaction to a real acquirer/gateway for online
                // authorization. No such gateway integration exists yet for the
                // ID TECH path (unlike PAX POSLink, which does bank comms inside
                // its own ProcessTrans call -- see docs/card_payment_integration.md).
                // Do NOT fabricate an approval response here: that would mean the
                // wash dispenses with no money ever having moved. Fail closed until
                // a real host-authorization call is wired in.
                Log.e(TAG, "EMV: Online host authorization required but not implemented -- declining, not approving")
                reader?.emv_completeTransaction(false, byteArrayOf(0x30, 0x35) /* "05" = Declined */, null, null, null)
                finishSale { currentCallback?.onFailure("Online authorization not yet implemented for ID TECH") }
            }
            EmvResultCategory.APPROVED -> {
                // Contact EMV and CTLS share this callback and the same
                // APPROVED/APPROVED_OFFLINE result codes -- there's no
                // documented way to tell them apart from emvData.result
                // alone. cardType is the closest available signal but its
                // value mapping isn't confirmed against vendor docs, so it's
                // surfaced raw rather than guessed at (e.g. decoded as
                // "EMV_CONTACT" vs "EMV_CTLS" without real-hardware
                // confirmation of what each cardType value means).
                finishSale {
                    currentCallback?.onSuccess(
                        "IDT_AUTH", "IDT_" + System.currentTimeMillis(),
                        entryMode = "EMV_OR_CTLS(cardType=${emvData.cardType})"
                    )
                }
            }
            EmvResultCategory.DECLINED -> {
                finishSale { currentCallback?.onFailure("Declined: $statusMsg") }
            }
            EmvResultCategory.CANCELLED -> {
                finishSale { currentCallback?.onFailure("Transaction Cancelled") }
            }
            EmvResultCategory.TIMEOUT -> {
                finishSale { currentCallback?.onFailure("Transaction Timeout") }
            }
            EmvResultCategory.FALLBACK -> {
                // MSR is already armed concurrently (see startSale), so in
                // principle the customer could just swipe now instead of
                // failing outright -- not resolving here since that's
                // unverified behavior on real hardware; keep the safer,
                // predictable "fail and let the customer retry" outcome.
                finishSale { currentCallback?.onFailure("Chip read failed -- please swipe or try another card") }
            }
            EmvResultCategory.CTLS_RETRY -> {
                finishSale { currentCallback?.onFailure("Tap not read -- please try again or insert/swipe your card") }
            }
            EmvResultCategory.VERIFICATION_UNSUPPORTED -> {
                // Real EMV flows can require these; no capture UI exists yet
                // (see docs/card_payment_integration.md §3.2 "签名确认流程").
                // Fail closed rather than silently skipping cardholder verification.
                Log.e(TAG, "EMV: $statusMsg required but no capture UI implemented")
                finishSale { currentCallback?.onFailure("This card requires verification not yet supported") }
            }
            EmvResultCategory.UNKNOWN -> {
                finishSale { currentCallback?.onFailure("EMV Status: $statusMsg (${emvData.result})") }
            }
        }
    }

    override fun swipeMSRData(card: IDTMSRData) {
        Log.i(TAG, "MSR data event received")
        // Check card.cardData[0] correctly for Kotlin
        val firstByte = if (card.cardData != null && card.cardData.isNotEmpty()) card.cardData[0] else 0.toByte()
        
        if (firstByte != 0x01.toByte() && card.track1Length == 0 && card.track2Length == 0) {
            finishSale { currentCallback?.onFailure("Bad Swipe Data") }
        } else {
            val masked = reader?.let { Common.parse_MSRData(it.device_getDeviceType(), card) }
            Log.d(TAG, "MSR Data (Masked): $masked")
            finishSale { currentCallback?.onSuccess("MSR_AUTH", "MSR_" + System.currentTimeMillis(), entryMode = "MSR") }
        }
    }

    override fun deviceConnected() {
        Log.i(TAG, "ID TECH Reader Connected")
    }

    override fun deviceDisconnected() {
        Log.w(TAG, "ID TECH Reader Disconnected")
        finishSale { currentCallback?.onFailure("Hardware Disconnected", isHardwareFault = true) }
    }

    override fun timeout(timeoutType: Int) {
        finishSale { currentCallback?.onFailure("Device Timeout") }
    }

    override fun lcdDisplay(mode: Int, lines: Array<out String>?, timeout: Int) {
        if (mode == 0x01 || mode == 0x08) {
            reader?.emv_lcdControlResponse(mode.toByte(), 0x01.toByte())
        }
        lines?.firstOrNull()?.let { currentCallback?.onProgress(it) }
    }

    override fun lcdDisplay(mode: Int, lines: Array<out String>?, timeout: Int, data: ByteArray?, type: Byte) {
        // Handle PIN
    }

    override fun LoadXMLConfigFailureInfo(errorCode: Int, errorMsg: String) {
        Log.e(TAG, "XML Config Load Fail: $errorMsg ($errorCode)")
    }
    
    override fun autoConfigCompleted(params: StructConfigParameters) {}
    override fun autoConfigProgress(progress: Int) {}

    /**
     * Raw CTLS framing/detection events (e.g. card-in-field, LED/beep triggers)
     * -- NOT the transaction outcome, which arrives via [emvTransactionData]'s
     * CTLS-specific result codes. Meaning of event/scheme bytes isn't
     * documented in what we have locally; logged only, for field diagnostics.
     */
    override fun ctlsEvent(event: Byte, scheme: Byte, data: ByteArray) {
        Log.d(TAG, "CTLS event: 0x%02X scheme=0x%02X (%d bytes)".format(event, scheme, data.size))
    }

    override fun dataInOutMonitor(data: ByteArray, isInput: Boolean) {}
    override fun msgAudioVolumeAdjustFailed() {}
    override fun msgBatteryLow() {}
    override fun msgRKICompleted(status: String) {}
    override fun msgToConnectDevice() {}
    override fun ICCNotifyInfo(data: ByteArray, msg: String) {}
}
