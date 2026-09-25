package com.goldsky.ssp.payment

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
    fun applyHeader(title: TextView, ticker: TextView, branding: Branding?, defaultTitle: String) {
        title.text = branding?.brand_name?.takeIf { it.isNotBlank() && it != "GS-SSP" } ?: defaultTitle
        val message = branding?.welcome_message?.takeIf { it.isNotBlank() }
        ticker.text = message.orEmpty()
        ticker.visibility = if (message == null) View.GONE else View.VISIBLE
    }
}
