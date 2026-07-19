package com.goldsky.carwash

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.airbnb.lottie.LottieAnimationView
import com.goldsky.carwash.payment.PaxScannerManager
import com.goldsky.carwash.payment.PaymentService
import com.goldsky.carwash.payment.VipRepository
import com.goldsky.carwash.serial.SerialPortManager
import kotlinx.coroutines.*


/**
 * Main Controller Activity. Displays service options, manages payment modals,
 * operates serial communication to trigger hardware relalys.
 */
class MainActivity : BaseAdActivity() {

    private lateinit var layoutPackageSelection: ConstraintLayout
    private lateinit var layoutWorking: ConstraintLayout

    // State Variables
    private var isWorking = false
    private var isSimulationMode = true // Set to false for real PAX hardware integration
    private var paymentDialog: Dialog? = null
    private var pollingJob: Job? = null
    private var scannerManager: PaxScannerManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init views
        layoutPackageSelection = findViewById(R.id.layout_package_selection)
        layoutWorking = findViewById(R.id.layout_working)

        scannerManager = PaxScannerManager(this)

        setupClickListeners()

        // Open serial port at launch using PAX NeptuneLite UART API
        if (!isSimulationMode) {
            SerialPortManager.openPort(this)
        }
    }

    override fun onResume() {
        super.onResume()
        applyKioskWindowFlags()
        resetAdTimer()
        // Enable scanner LED for voucher scan on main menu
        scannerManager?.setScannerLed(true)
    }

    override fun onPause() {
        super.onPause()
        stopAdTimer()
        // Disable scanner LED when leaving main menu
        scannerManager?.setScannerLed(false)
    }

    private fun setupClickListeners() {
        // Long press logo to toggle Simulation Mode (for demo purposes)
        findViewById<View>(R.id.img_logo).setOnLongClickListener {
            isSimulationMode = !isSimulationMode
            val modeMsg = if (isSimulationMode) getString(R.string.toast_mode_demo) else getString(R.string.toast_mode_prod)
            Toast.makeText(this, modeMsg, Toast.LENGTH_SHORT).show()
            true
        }

        findViewById<View>(R.id.card_standard).setOnClickListener {
            showPaymentDialog(400, "AA 01 04 55")
        }

        findViewById<View>(R.id.card_delux).setOnClickListener {
            showPaymentDialog(600, "AA 01 06 55")
        }

        findViewById<View>(R.id.card_wax).setOnClickListener {
            showPaymentDialog(800, "AA 01 08 55")
        }

        findViewById<View>(R.id.card_custom).setOnClickListener {
            showCustomAmountDialog()
        }

        findViewById<View>(R.id.layout_vip_banner).setOnClickListener {
            startActivity(Intent(this, VipActivity::class.java))
        }
    }

    private fun showCustomAmountDialog() {
        stopAdTimer()
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_custom_amount)
        
        var amount = 10
        val tvAmount = dialog.findViewById<TextView>(R.id.tv_amount_display)
        
        dialog.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if (amount < 50) {
                amount += 2
                tvAmount.text = "$$amount"
            }
        }
        
        dialog.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if (amount > 2) {
                amount -= 2
                tvAmount.text = "$$amount"
            }
        }
        
        dialog.findViewById<Button>(R.id.btn_confirm_custom).setOnClickListener {
            dialog.dismiss()
            val hex = "AA 01 ${"%02X".format(amount)} 55"
            showPaymentDialog(amount * 100, hex)
        }
        
        dialog.findViewById<Button>(R.id.btn_cancel_custom).setOnClickListener {
            dialog.dismiss()
            resetAdTimer()
        }
        
        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.show()
    }

    /**
     * Overrides parent check to prevent launching AdActivity while wash is active.
     */
    override fun isCarWashSessionActive(): Boolean {
        return isWorking || (paymentDialog != null && paymentDialog!!.isShowing)
    }

    /**
     * Entry point for payment. Shows selection dialog first.
     */
    private fun showPaymentDialog(priceInCents: Int, startHex: String) {
        stopAdTimer()
        
        val selectionDialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        selectionDialog.setContentView(R.layout.dialog_payment_selection)
        
        selectionDialog.findViewById<View>(R.id.btn_choice_card).setOnClickListener {
            selectionDialog.dismiss()
            startPaymentFlow(true, priceInCents, startHex)
        }
        
        selectionDialog.findViewById<View>(R.id.btn_choice_scan).setOnClickListener {
            selectionDialog.dismiss()
            startPaymentFlow(false, priceInCents, startHex)
        }
        
        selectionDialog.findViewById<Button>(R.id.btn_cancel_choice).setOnClickListener {
            selectionDialog.dismiss()
            resetAdTimer()
        }
        
        selectionDialog.setOnShowListener { applyKioskWindowFlags() }
        selectionDialog.show()
    }

    private fun startPaymentFlow(isCard: Boolean, priceInCents: Int, startHex: String) {
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_payment)
        paymentDialog = dialog

        val layoutCard = dialog.findViewById<ConstraintLayout>(R.id.layout_card_guidance)
        val layoutQr = dialog.findViewById<ConstraintLayout>(R.id.layout_qr_guidance)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.shared_subtitle)
        val pbTimeout = dialog.findViewById<ProgressBar>(R.id.pb_pay_timeout)
        
        tvSubtitle.text = getString(R.string.prompt_pay_subtitle, "$${priceInCents / 100}")

        if (isCard) {
            layoutCard.visibility = View.VISIBLE
            layoutQr.visibility = View.GONE
            startTapCardAnimation(dialog)
            
            // VOICE: "Please tap, swipe or insert your card"
            VoiceManager.playPleaseTap(this)

            // Start background NFC detection to distinguish between EMV and VIP
            scannerManager?.startCardDetection(object : PaxScannerManager.CardCallback {
                override fun onCardDetected(type: String, uid: String) {
                    if (type == "MIFARE") {
                        // VIP Membership detected
                        initVipPayment(uid, priceInCents, startHex, dialog)
                    } else {
                        // Payment Card (EMV) detected - Hand over to POSLink
                        // Critical: We must close the low-level PICC before POSLink takes over
                        scannerManager?.stopCardDetection()
                        initCardPayment(priceInCents, startHex, dialog)
                    }
                }
                override fun onDetectionError(error: String) {
                    Log.e("MainActivity", "NFC detection error: $error")
                }
            })
        } else {
            layoutCard.visibility = View.GONE
            layoutQr.visibility = View.VISIBLE
            initQrPayment(priceInCents, startHex, dialog)
        }

        dialog.findViewById<View>(R.id.btn_back_pay)?.setOnClickListener {
            dialog.dismiss()
            showPaymentDialog(priceInCents, startHex)
        }
        dialog.findViewById<View>(R.id.btn_back_qr)?.setOnClickListener {
            dialog.dismiss()
            showPaymentDialog(priceInCents, startHex)
        }

        val dialogTimer = object : CountDownTimer(60000, 100) {
            override fun onTick(millisUntilFinished: Long) {
                pbTimeout.progress = (millisUntilFinished / 1000).toInt()
            }
            override fun onFinish() {
                if (dialog.isShowing) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_pay_timeout), Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    resetAdTimer()
                }
            }
        }
        dialogTimer.start()

        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.setOnDismissListener {
            dialogTimer.cancel()
            scannerManager?.stopScan()
            pollingJob?.cancel()
        }
        dialog.show()
    }

    private fun startTapCardAnimation(dialog: Dialog) {
        val animatedCard = dialog.findViewById<View>(R.id.animated_card)
        ObjectAnimator.ofFloat(animatedCard, "translationY", 100f, -50f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun initVipPayment(uid: String, priceInCents: Int, startHex: String, dialog: Dialog) {
        val layoutStatus = dialog.findViewById<ConstraintLayout>(R.id.layout_status_overlay)
        val tvStatus = dialog.findViewById<TextView>(R.id.tv_status_msg)

        layoutStatus.visibility = View.VISIBLE
        tvStatus.text = "VIP Card Detected\nVerifying Balance..."

        CoroutineScope(Dispatchers.Main).launch {
            val success = VipRepository.deductBalance(uid, priceInCents)
            if (success) {
                tvStatus.text = "VIP Payment Successful!"
                delay(1500)
                startFinalizationSequence(startHex, "VIP_$uid", dialog)
            } else {
                Toast.makeText(this@MainActivity, "VIP Card Balance Insufficient", Toast.LENGTH_LONG).show()
                VoiceManager.playLowBalance(this@MainActivity)
                layoutStatus.visibility = View.GONE
                // Restart detection
                startPaymentFlow(true, priceInCents, startHex) 
            }
        }
    }

    private fun initCardPayment(priceInCents: Int, startHex: String, dialog: Dialog) {
        if (isSimulationMode) {
            CoroutineScope(Dispatchers.Main).launch {
                delay(3000)
                startFinalizationSequence(startHex, "MOCK_REF_123", dialog)
            }
        } else {
            PaymentService.startCardPayment(priceInCents, object : PaymentService.PaymentCallback {
                override fun onSuccess(txId: String, refNum: String) {
                    startFinalizationSequence(startHex, refNum, dialog)
                }
                override fun onFailure(errorMsg: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Card Payment Failed: $errorMsg", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        resetAdTimer()
                    }
                }
            })
        }
    }

    private fun initQrPayment(priceInCents: Int, startHex: String, dialog: Dialog) {
        val qrImageView = dialog.findViewById<ImageView>(R.id.img_pay_qr)
        val txId = "TX_" + System.currentTimeMillis()
        val checkoutUrl = "https://gs-ssp.ca/pay?tx=$txId&amt=${priceInCents}"
        val qrBitmap: Bitmap? = PaymentService.generateQrCode(checkoutUrl, 250, 250)
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap)
        }

        pollingJob = CoroutineScope(Dispatchers.Main).launch {
            PaymentService.pollPaymentStatus(txId) { success ->
                if (success) {
                    startFinalizationSequence(startHex, "", dialog)
                }
            }
        }

        scannerManager?.startScan(object : PaxScannerManager.ScanCallback {
            override fun onScanSuccess(result: String) {
                startFinalizationSequence(startHex, "", dialog)
            }
            override fun onScanFailure(errorMsg: String) {}
        })
    }

    /**
     * Sequence of confirmation stages after tap/scan success.
     */
    private fun startFinalizationSequence(startHex: String, refNum: String, dialog: Dialog?) {
        val layoutStatus = dialog?.findViewById<ConstraintLayout>(R.id.layout_status_overlay)
        val tvStatus = dialog?.findViewById<TextView>(R.id.tv_status_msg)
        val viewSuccessBg = dialog?.findViewById<View>(R.id.view_final_success_bg)
        
        CoroutineScope(Dispatchers.Main).launch {
            layoutStatus?.visibility = View.VISIBLE
            tvStatus?.text = getString(R.string.status_approved)
            VoiceManager.playApproved(this@MainActivity)
            delay(1200)

            tvStatus?.text = getString(R.string.status_paid)
            delay(1000)

            tvStatus?.text = getString(R.string.status_sending_command)
            
            var successAck = false
            if (isSimulationMode) {
                delay(1500)
                successAck = true
            } else {
                val sent = SerialPortManager.sendHexString(startHex)
                if (sent) {
                    delay(500) 
                    successAck = true
                }
            }

            if (successAck) {
                viewSuccessBg?.visibility = View.VISIBLE
                tvStatus?.text = getString(R.string.status_enjoy_wash)
                VoiceManager.playEnjoyWash(this@MainActivity)
                viewSuccessBg?.alpha = 0f
                viewSuccessBg?.animate()?.alpha(1f)?.setDuration(500)?.start()
                
                delay(5000)
                dialog?.dismiss()
                onPaymentSuccess()
            } else {
                layoutStatus?.setBackgroundColor(Color.parseColor("#C62828"))
                tvStatus?.text = getString(R.string.status_error_refund)
                VoiceManager.playDeclined(this@MainActivity)
                if (refNum.isNotEmpty()) {
                    PaymentService.voidTransaction(refNum)
                }
                delay(5000)
                dialog?.dismiss()
                resetAdTimer()
            }
        }
    }

    private fun onPaymentSuccess() {
        runOnUiThread {
            paymentDialog = null
            layoutPackageSelection.visibility = View.VISIBLE
            isWorking = false
            resetAdTimer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        paymentDialog?.dismiss()
        pollingJob?.cancel()
        scannerManager?.stopScan()
        if (!isSimulationMode) {
            SerialPortManager.closePort()
        }
    }
}
