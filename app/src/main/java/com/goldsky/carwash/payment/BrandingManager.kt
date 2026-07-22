package com.goldsky.carwash.payment

import android.widget.ImageView
import coil.load
import coil.request.CachePolicy
import com.goldsky.carwash.R
import com.goldsky.carwash.model.Branding

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
}
