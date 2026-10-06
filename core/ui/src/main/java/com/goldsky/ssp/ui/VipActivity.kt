package com.goldsky.ssp.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.core.ui.R
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.VipLoadPlan
import com.goldsky.ssp.payment.VipLoadRepository
import kotlinx.coroutines.launch

/**
 * VIP membership page: the digital pass, its benefits, and the merchant's
 * load plans as tiles that start a purchase / top-up (VipPurchaseFlow).
 * Offline or no plans: the how-to guide instead of tiles.
 */
class VipActivity : BaseAdActivity() {

    companion object {
        // Shorter and separate from BaseAdActivity's own 3-minute ad-idle
        // timer -- an idle customer here just goes back to the wash screen.
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

        findViewById<VipCardView>(R.id.vip_card_face).brandName = brandName()

        // The BACK button was once left unwired -- with the kiosk's hidden
        // nav bar that made this page a dead end (found on-device 2026-08-29).
        findViewById<Button>(R.id.btn_back_main).setOnClickListener {
            if (!purchaseActive) finish()
        }

        loadPlans()
    }

    /** The merchant name for the pass: the configured brand name without a "Welcome to" greeting. */
    private fun brandName(): String =
        ConfigManager.getConfig()?.branding?.brand_name
            ?.replace(Regex("""^\s*welcome\s+to\s+""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeUnless { it.isBlank() || it == "GS-SSP" } ?: "VIP CLUB"

    /** Fills the right column with the merchant's plans (CMP, vip_load_plans); keeps the guide if there are none. */
    private fun loadPlans() {
        lifecycleScope.launch {
            val plans = VipLoadRepository.getPlans()?.take(3)
            if (plans.isNullOrEmpty()) return@launch
            val container = findViewById<LinearLayout>(R.id.layout_tiers)
            container.removeAllViews()
            val best = bestValue(plans)
            plans.forEach { plan -> container.addView(tile(container, plan, plan == best)) }
            container.visibility = View.VISIBLE
            findViewById<View>(R.id.layout_vip_guide).visibility = View.GONE
        }
    }

    private fun tile(parent: LinearLayout, plan: VipLoadPlan, best: Boolean): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_vip_plan, parent, false)
        v.findViewById<TextView>(R.id.tv_plan_pay).text = VipLoadPlan.money(plan.amount_cents)
        v.findViewById<TextView>(R.id.tv_plan_get).text = getString(R.string.vip_tile_get, VipLoadPlan.money(plan.totalCents))
        v.findViewById<TextView>(R.id.tv_plan_bonus).apply {
            val pct = bonusPercent(plan)
            if (pct > 0) text = getString(R.string.vip_tile_bonus, pct) else visibility = View.GONE
        }
        if (best) {
            v.setBackgroundResource(R.drawable.bg_vip_tile_best)
            v.findViewById<View>(R.id.tv_plan_best).visibility = View.VISIBLE
        }
        v.setOnClickListener {
            if (!purchaseActive) VipPurchaseFlow(this, plan, brandName()) { active ->
                purchaseActive = active
                resetIdleTimer()
            }.start()
        }
        return v
    }

    private fun bonusPercent(plan: VipLoadPlan) = plan.bonus_cents * 100 / plan.amount_cents

    /** The single plan with the highest bonus ratio; none if tied or no bonus at all. */
    private fun bestValue(plans: List<VipLoadPlan>): VipLoadPlan? {
        val top = plans.maxByOrNull { it.bonus_cents.toDouble() / it.amount_cents } ?: return null
        val topRatio = top.bonus_cents.toDouble() / top.amount_cents
        if (topRatio <= 0 || plans.count { it.bonus_cents.toDouble() / it.amount_cents == topRatio } > 1) return null
        return top
    }
}
