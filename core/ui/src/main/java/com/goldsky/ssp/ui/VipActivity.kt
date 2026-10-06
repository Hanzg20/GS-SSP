package com.goldsky.ssp.ui

import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.view.Gravity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.payment.VipLoadPlan
import com.goldsky.ssp.payment.VipLoadRepository
import kotlinx.coroutines.launch
import com.goldsky.ssp.core.ui.R

class VipActivity : BaseAdActivity() {

    companion object {
        // Shorter and separate from BaseAdActivity's own 3-minute ad-idle
        // timer -- this page has nothing for an idle customer to be shown
        // (no ad content makes sense mid-VIP-purchase), so it just backs out
        // to the price selection screen instead of waiting for the ad timer.
        private const val VIP_IDLE_TIMEOUT_MS = 60000L
    }

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { finish() }

    // A purchase/top-up is on screen: never back out from under it (the
    // flow has its own timeouts and must not lose a paid sale's result).
    private var purchaseActive = false

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        if (!purchaseActive) idleHandler.postDelayed(idleRunnable, VIP_IDLE_TIMEOUT_MS)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(idleRunnable)
    }

    override fun onResume() {
        super.onResume()
        resetIdleTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        idleHandler.removeCallbacks(idleRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vip)

        // Round the card preview's corners to match the rest of the app's
        // card aesthetic (same 16dp radius as bg_glass_card). img_vip_card
        // uses fitCenter (the full source photo, nothing cropped off), so it
        // doesn't necessarily fill layout_card_bg's whole box -- both views
        // share the same bounds and need the same outline, or the letterbox
        // fill behind a narrower image would show square corners poking out.
        val cardRadiusPx = 16f * resources.displayMetrics.density
        val roundedCornerOutline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cardRadiusPx)
            }
        }
        findViewById<View>(R.id.layout_card_bg).apply {
            clipToOutline = true
            outlineProvider = roundedCornerOutline
        }
        findViewById<ImageView>(R.id.img_vip_card).apply {
            clipToOutline = true
            outlineProvider = roundedCornerOutline
        }

        // The layout's BACK button was never wired up -- combined with
        // BaseAdActivity's immersive full-screen kiosk flags (nav bar
        // hidden), this screen was a dead end with no way back to the wash
        // home screen. Found live on-device 2026-08-29.
        findViewById<Button>(R.id.btn_back_main).setOnClickListener {
            if (!purchaseActive) finish()
        }

        loadPlans()
    }

    /**
     * Replaces the layout's static tier rows with the merchant's load plans
     * (CMP, vip_load_plans) and makes them buyable. Offline / no plans: the
     * static rows stay as information and the guide keeps "ask the attendant".
     */
    private fun loadPlans() {
        lifecycleScope.launch {
            val plans = VipLoadRepository.getPlans()?.take(3)
            if (plans.isNullOrEmpty()) return@launch
            val container = findViewById<LinearLayout>(R.id.layout_tiers)
            val template = container.getChildAt(0) as? LinearLayout ?: return@launch
            val rowParams = template.layoutParams
            container.removeAllViews()
            plans.forEach { plan ->
                val row = LinearLayout(this@VipActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    background = template.background?.constantState?.newDrawable()
                    elevation = template.elevation
                    setPadding(template.paddingLeft, template.paddingTop, template.paddingRight, template.paddingBottom)
                    isClickable = true
                    setOnClickListener {
                        if (!purchaseActive) VipPurchaseFlow(this@VipActivity, plan) { active ->
                            purchaseActive = active
                            resetIdleTimer()
                        }.start()
                    }
                }
                row.addView(label(getString(R.string.vip_plan_load, VipLoadPlan.money(plan.amount_cents)), R.color.text_light, 14f))
                row.addView(label("→", R.color.text_muted, 14f).apply { setPadding(12, 0, 12, 0) })
                row.addView(label(getString(R.string.vip_plan_total, VipLoadPlan.money(plan.totalCents)), R.color.gold_accent, 17f))
                container.addView(row, LinearLayout.LayoutParams(rowParams))
            }
            findViewById<TextView>(R.id.tv_vip_guide_buy)?.setText(R.string.vip_guide_buy_body_terminal)
        }
    }

    private fun label(text: String, color: Int, sizeSp: Float) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@VipActivity, color))
        textSize = sizeSp
        maxLines = 1
        setTypeface(typeface, Typeface.BOLD)
    }
}
