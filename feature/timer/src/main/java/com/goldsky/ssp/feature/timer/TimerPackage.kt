package com.goldsky.ssp.feature.timer

import com.goldsky.ssp.model.Product
import com.goldsky.ssp.model.forVertical

/** One sellable block of time, e.g. $2 -> 4 min. */
data class TimerPackage(
    val productId: String,
    val name: String,
    val priceCents: Int,
    val durationSec: Int,
) {
    val durationMs: Long get() = durationSec * 1000L

    companion object {
        const val VERTICAL = "TIMER"

        /**
         * Sellable TIMER packages out of an org's product list, cheapest first.
         * app_configurations is per-org, not per-device, so a site running
         * both wash and Timer terminals gets one list with both kinds in it --
         * everything that isn't vertical_type TIMER is dropped here. A TIMER
         * product with no positive duration_sec is dropped too: charging for
         * an unknown amount of time is worse than not offering it.
         */
        fun fromProducts(products: List<Product>): List<TimerPackage> =
            products.forVertical(VERTICAL)
                .filter { it.is_active && it.price_cents > 0 }
                .mapNotNull { p ->
                    val sec = p.durationSec?.takeIf { it > 0 } ?: return@mapNotNull null
                    TimerPackage(p.id, p.name, p.price_cents, sec)
                }
                .sortedBy { it.priceCents }
    }
}
