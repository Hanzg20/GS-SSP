package com.goldsky.ssp.feature.laundry

import com.goldsky.ssp.model.Product
import com.goldsky.ssp.model.forVertical
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

const val LAUNDRY_VERTICAL = "LAUNDRY"

/**
 * One washer or dryer on this terminal's relay module, from a Cael product with
 * vertical_type LAUNDRY. products has no columns for this, so it rides in `attributes`
 * (as Aegis Timer's duration_sec does):
 *
 *   machine_no   number shown to customers and printed on the machine (required)
 *   relay        relay module channel wired to its coin-drop input, 1..8 (required)
 *   kind         "WASHER" | "DRYER" (default WASHER)
 *   coin_cents   what one coin pulse is worth to the machine (default 25)
 *   pulse_ms     how long each pulse closes the contact (default 100)
 *   gap_ms       pause between pulses (default 150)
 *   duration_min cycle length, for the "in use" estimate (optional)
 *   input        module input wired to the machine's "running" output (optional)
 *
 * The product's price is what the customer pays; the machine is sent
 * price_cents / coin_cents pulses, so a price that isn't a whole number of coins
 * can't be sold (the machine would be under- or over-credited).
 */
data class LaundryMachineConfig(
    val productId: String,
    val name: String,
    val machineNo: Int,
    val kind: Kind,
    val priceCents: Int,
    val relay: Int,
    val coinCents: Int,
    val pulseMs: Long,
    val gapMs: Long,
    val durationMin: Int?,
    val input: Int?,
) {
    enum class Kind { WASHER, DRYER }

    val pulses: Int get() = priceCents / coinCents

    companion object {
        const val MAX_RELAY = 8
        const val DEFAULT_COIN_CENTS = 25
        const val DEFAULT_PULSE_MS = 100L
        const val DEFAULT_GAP_MS = 150L

        /** The machine [p] describes, or why it can't be sold. */
        fun from(p: Product): Result<LaundryMachineConfig> {
            fun int(key: String) = p.attributes?.get(key)?.jsonPrimitive?.intOrNull
            val no = int("machine_no") ?: return fail(p, "no machine_no")
            val relay = int("relay") ?: return fail(p, "no relay")
            if (relay !in 1..MAX_RELAY) return fail(p, "relay $relay is not 1..$MAX_RELAY")
            val coin = int("coin_cents") ?: DEFAULT_COIN_CENTS
            if (coin <= 0) return fail(p, "coin_cents must be positive")
            if (p.price_cents <= 0 || p.price_cents % coin != 0)
                return fail(p, "price ${p.price_cents} is not a whole number of ${coin}-cent pulses")
            val kind = when (p.attributes?.get("kind")?.jsonPrimitive?.contentOrNull?.uppercase()) {
                null, "WASHER" -> Kind.WASHER
                "DRYER" -> Kind.DRYER
                else -> return fail(p, "kind must be WASHER or DRYER")
            }
            val pulseMs = (int("pulse_ms") ?: DEFAULT_PULSE_MS.toInt()).toLong()
            val gapMs = (int("gap_ms") ?: DEFAULT_GAP_MS.toInt()).toLong()
            if (pulseMs !in 20..2000 || gapMs !in 20..2000) return fail(p, "pulse_ms / gap_ms must be 20..2000")
            return Result.success(
                LaundryMachineConfig(
                    productId = p.id, name = p.name, machineNo = no, kind = kind, priceCents = p.price_cents,
                    relay = relay, coinCents = coin, pulseMs = pulseMs, gapMs = gapMs,
                    durationMin = int("duration_min"), input = int("input")?.takeIf { it in 1..MAX_RELAY },
                )
            )
        }

        private fun fail(p: Product, why: String) =
            Result.failure<LaundryMachineConfig>(IllegalArgumentException("${p.name}: $why"))

        /**
         * This terminal's machines, in machine-number order, plus the products that
         * couldn't be used and why (shown to the technician, never sold). Two products on
         * one relay or with one machine number are both refused: a payment must never
         * start a different machine than the one the customer picked.
         */
        fun fromProducts(products: List<Product>): Pair<List<LaundryMachineConfig>, List<String>> {
            val problems = mutableListOf<String>()
            val ok = products.forVertical(LAUNDRY_VERTICAL).filter { it.is_active }.mapNotNull { p ->
                from(p).onFailure { problems += it.message ?: p.name }.getOrNull()
            }
            val clashes = ok.groupBy { it.relay }.filterValues { it.size > 1 }.values.flatten() +
                ok.groupBy { it.machineNo }.filterValues { it.size > 1 }.values.flatten()
            clashes.distinct().forEach { problems += "${it.name}: relay ${it.relay} / machine ${it.machineNo} is used twice" }
            return (ok - clashes.toSet()).sortedBy { it.machineNo } to problems
        }
    }
}
