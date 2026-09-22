package com.goldsky.ssp.payment

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
     * Sets a merchant-facing welcome text, added 2026-09-23 alongside fixing
     * the CMP->Kotlin branding field-name mismatch (see Branding.welcome_message's
     * doc comment). Fallback chain, in order:
     * 1. [Branding.welcome_message] verbatim, if the merchant published one.
     * 2. "Welcome to {brand_name}", if the merchant set a brand name but no
     *    full custom message -- brand_name's own default is the literal
     *    string "GS-SSP", so that default is deliberately excluded here
     *    (showing "Welcome to GS-SSP" on every un-configured device would
     *    look like a real merchant name, not an empty/default state).
     * 3. [defaultText] -- the caller's own existing static string resource,
     *    unchanged from before this function existed.
     */
    fun applyWelcomeText(textView: TextView, branding: Branding?, defaultText: String) {
        val brandName = branding?.brand_name?.takeIf { it.isNotBlank() && it != "GS-SSP" }
        textView.text = branding?.welcome_message?.takeIf { it.isNotBlank() }
            ?: brandName?.let { "Welcome to $it" }
            ?: defaultText
    }
}
