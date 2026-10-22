package com.goldsky.ssp.payment

import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.request.CachePolicy
import com.goldsky.ssp.core.ui.R
import com.goldsky.ssp.model.Branding

/**
 * Manages dynamic branding assets (Logos, Colors) per tenant.
 */
object BrandingManager {

    /**
     * Loads the tenant logo into the provided ImageView with local caching.
     */
    fun applyLogo(imageView: ImageView, branding: Branding?) {
        val url = branding?.logo_url
        if (url.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_goldsky_logo)
            return
        }

        imageView.load(url) {
            crossfade(true)
            placeholder(R.drawable.ic_goldsky_logo)
            error(R.drawable.ic_goldsky_logo)
            diskCachePolicy(CachePolicy.ENABLED)
            memoryCachePolicy(CachePolicy.ENABLED)
        }
    }

    /**
     * Fills the kiosk header the same way Aegis Timer's HomeScreen does
     * (TimerViewModel: title = brand_name, subtitle = welcome_message):
     * - [title]: the merchant's brand name; the Branding default "GS-SSP" or a
     *   blank name counts as unset and shows [defaultTitle] instead.
     * - [ticker]: the merchant announcement (CMP "Kiosk Marquee
     *   Announcement", [Branding.welcome_message]); hidden when none is
     *   published.
     */
    // Header sizes: brand name over the announcement ticker; when only one of the two is
    // filled in, it takes the height of both (customer request, 2026-10-21) -- ONE_LINE_SP
    // is about the two lines plus the gap between them.
    private const val TITLE_SP = 18f
    private const val TICKER_SP = 16f
    private const val ONE_LINE_SP = 36f

    fun applyHeader(title: TextView, ticker: TextView, branding: Branding?, defaultTitle: String) {
        val name = branding?.brand_name?.takeIf { it.isNotBlank() && it != "GS-SSP" }
        val message = branding?.welcome_message?.takeIf { it.isNotBlank() }
        ticker.text = message.orEmpty()
        when {
            // Name only (or nothing: the default title): one big line, shrunk to fit if long.
            message == null -> {
                title.visibility = View.VISIBLE
                title.text = name ?: defaultTitle
                ticker.visibility = View.GONE
                fitOneLine(title, ONE_LINE_SP, TITLE_SP)
            }
            // Announcement only: it scrolls anyway, so it simply gets the big size.
            name == null -> {
                title.visibility = View.GONE
                ticker.visibility = View.VISIBLE
                ticker.setTextSize(TypedValue.COMPLEX_UNIT_SP, ONE_LINE_SP)
            }
            else -> {
                title.visibility = View.VISIBLE
                title.text = name
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
                ticker.visibility = View.VISIBLE
                ticker.setTextSize(TypedValue.COMPLEX_UNIT_SP, TICKER_SP)
            }
        }
    }

    /** [maxSp], or smaller (not under [minSp]) until the text fits the view's width on one line. */
    private fun fitOneLine(view: TextView, maxSp: Float, minSp: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxSp)
        view.post {
            val available = view.width - view.paddingLeft - view.paddingRight
            if (available <= 0) return@post
            val text = view.text.toString()
            val paint = Paint(view.paint)
            var sp = maxSp
            while (sp > minSp) {
                paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, view.resources.displayMetrics)
                if (paint.measureText(text) <= available) break
                sp -= 1f
            }
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }
    }
}
