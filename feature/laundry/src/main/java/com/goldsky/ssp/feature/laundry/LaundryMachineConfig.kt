package com.goldsky.ssp.feature.laundry

import com.goldsky.ssp.model.Product
import com.goldsky.ssp.model.forVertical
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 *   size_lb      washer capacity shown to customers (optional, e.g. 20 / 40 / 60)
 *   coin_cents   what one coin pulse is worth to the machine (default 25)
 *   pulse_count  single-pulse machines only: pulses that start a cycle (then the price is
 *                fixed: no temperatures / add-ons / dryer times -- the machine can't be told)
 *   pulse_ms     how long each pulse closes the contact (default 50)
 *   gap_ms       pause between pulses (default 100)
 *   duration_min washer cycle length, for the "in use" estimate (optional)
 *   input        module input wired to the machine's "running" output (optional)
 *   temps        washer temperatures: [{"name":"Cold","add_cents":0}, ...]
 *                (default Cold +0 / Warm +50 / Hot +100; [] = none)
 *   addons       washer add-ons: [{"name":"Heavy soil","add_cents":50}, ...]
 *                (default Heavy soil +50 / Extra rinse +25; [] = none)
 *   dry_minutes  dryer minutes the product price buys (default 12)
 *   dry_steps    dryer time choices: 1..n times that (default 4: 12/24/36/48 min)
 *
 * How Canadian laundromats sell (docs/laundry_machines.md): washers by size, with a price
 * per cycle (cold/warm/hot) and add-ons; dryers by time ($1 per 8-12 min). The machine
 * counts our pulses as coins (coin_cents each) and lets the customer run any cycle the
 * credit covers -- so every price here must be a whole number of coins, and must match
 * the price programmed on the machine for that cycle.
 */
data class LaundryMachineConfig(
    val productId: String,
    val name: String,
    val machineNo: Int,
    val kind: Kind,
    /** Washer: base (cheapest) cycle. Dryer: price of one [dryMinutes] step. */
    val priceCents: Int,
    val relay: Int,
    val coinCents: Int,
    val pulseMs: Long,
    val gapMs: Long,
    val durationMin: Int?,
    val input: Int?,
    val sizeLb: Int? = null,
    /** Single-pulse machine: always this many pulses, fixed price, no options. */
    val singlePulses: Int? = null,
    val temps: List<Option> = emptyList(),
    val addons: List<Option> = emptyList(),
    val dryMinutes: Int = DEFAULT_DRY_MINUTES,
    val drySteps: Int = DEFAULT_DRY_STEPS,
) {
    enum class Kind { WASHER, DRYER }

    data class Option(val label: String, val addCents: Int)
    data class DryTime(val minutes: Int, val priceCents: Int)

    val isWasher get() = kind == Kind.WASHER

    /** Dryer time choices (none for a single-pulse dryer). */
    val dryTimes: List<DryTime>
        get() = if (isWasher || singlePulses != null) emptyList() else (1..drySteps).map { DryTime(dryMinutes * it, priceCents * it) }

    /** Cheapest the customer can pay ("from $6.00"). */
    val fromCents: Int get() = priceCents + (temps.minOfOrNull { it.addCents } ?: 0)

    /** What the customer chose on the confirm screen: the price and the pulses follow it. */
    data class Order(
        val machine: LaundryMachineConfig,
        val temp: Option? = machine.temps.firstOrNull(),
        val addons: Set<Option> = emptySet(),
        val dry: DryTime? = machine.dryTimes.getOrNull(minOf(2, machine.dryTimes.size - 1)),
    ) {
        val totalCents: Int
            get() = if (machine.isWasher) machine.priceCents + (temp?.addCents ?: 0) + addons.sumOf { it.addCents }
            else dry?.priceCents ?: machine.priceCents

        val pulses: Int get() = machine.singlePulses ?: (totalCents / machine.coinCents)

        /** For the customer and the record: "Hot · Heavy soil", "36 min". */
        val summary: String
            get() = if (machine.isWasher) (listOfNotNull(temp?.label) + machine.addons.filter { it in addons }.map { it.label }).joinToString(" · ")
            else dry?.let { "${it.minutes} min" } ?: machine.durationMin?.let { "$it min" }.orEmpty()

        /** How long the machine should be busy, for the "in use" estimate. */
        val minutes: Int? get() = if (machine.isWasher) machine.durationMin else dry?.minutes ?: machine.durationMin
    }

    companion object {
        const val MAX_RELAY = 8
        const val DEFAULT_COIN_CENTS = 25
        // Coin acceptors send 20-100 ms pulses (25/45/65 or 20/50/100 ms switch settings);
        // 50 ms is the common middle, and a pulse that is too long can raise a machine's
        // "coin error". docs/laundry_machines.md.
        const val DEFAULT_PULSE_MS = 50L
        const val DEFAULT_GAP_MS = 100L
        const val DEFAULT_DRY_MINUTES = 12
        const val DEFAULT_DRY_STEPS = 4
        val DEFAULT_TEMPS = listOf(Option("Cold", 0), Option("Warm", 50), Option("Hot", 100))
        val DEFAULT_ADDONS = listOf(Option("Heavy soil", 50), Option("Extra rinse", 25))

        /**
         * Demo-mode machines (a terminal with no LAUNDRY products yet): a small Toronto
         * laundromat (2025 prices: 20 lb $6, 40 lb $10, dryers $1 per 12 min). Never
         * charged or recorded.
         */
        val DEMO: List<LaundryMachineConfig> = (1..8).map { n ->
            val washer = n <= 5
            val big = n in 4..5
            LaundryMachineConfig(
                productId = LaundryViewModel.DEMO_PREFIX + n, name = if (washer) "Washer $n" else "Dryer $n", machineNo = n,
                kind = if (washer) Kind.WASHER else Kind.DRYER, priceCents = if (!washer) 100 else if (big) 1000 else 600,
                relay = n, coinCents = DEFAULT_COIN_CENTS, pulseMs = 40, gapMs = 40,
                durationMin = if (washer) 30 else null, input = null, sizeLb = if (!washer) null else if (big) 40 else 20,
                temps = if (washer) DEFAULT_TEMPS else emptyList(), addons = if (washer) DEFAULT_ADDONS else emptyList(),
            )
        }

        /** The machine [p] describes, or why it can't be sold. */
        fun from(p: Product): Result<LaundryMachineConfig> {
            val a = p.attributes
            fun int(key: String) = a?.get(key)?.jsonPrimitive?.intOrNull
            val no = int("machine_no") ?: return fail(p, "no machine_no")
            val relay = int("relay") ?: return fail(p, "no relay")
            if (relay !in 1..MAX_RELAY) return fail(p, "relay $relay is not 1..$MAX_RELAY")
            val coin = int("coin_cents") ?: DEFAULT_COIN_CENTS
            if (coin <= 0) return fail(p, "coin_cents must be positive")
            if (p.price_cents <= 0) return fail(p, "no price")
            val single = int("pulse_count")
            if (single != null && single !in 1..200) return fail(p, "pulse_count must be 1..200")
            val kind = when (a?.get("kind")?.jsonPrimitive?.contentOrNull?.uppercase()) {
                null, "WASHER" -> Kind.WASHER
                "DRYER" -> Kind.DRYER
                else -> return fail(p, "kind must be WASHER or DRYER")
            }
            val pulseMs = (int("pulse_ms") ?: DEFAULT_PULSE_MS.toInt()).toLong()
            val gapMs = (int("gap_ms") ?: DEFAULT_GAP_MS.toInt()).toLong()
            if (pulseMs !in 20..2000 || gapMs !in 20..2000) return fail(p, "pulse_ms / gap_ms must be 20..2000")

            // Coin-pulse machines: options, every price a whole number of coins.
            val washer = kind == Kind.WASHER
            val temps = if (single != null || !washer) emptyList() else options(a, "temps") ?: DEFAULT_TEMPS
            val addons = if (single != null || !washer) emptyList() else options(a, "addons") ?: DEFAULT_ADDONS
            val dryMinutes = int("dry_minutes") ?: DEFAULT_DRY_MINUTES
            val drySteps = int("dry_steps") ?: DEFAULT_DRY_STEPS
            if (!washer && (dryMinutes !in 1..120 || drySteps !in 1..8)) return fail(p, "dry_minutes 1..120 and dry_steps 1..8")
            if (single == null) {
                val prices = listOf(p.price_cents) + (temps + addons).map { it.addCents }
                prices.firstOrNull { it < 0 || it % coin != 0 }?.let {
                    return fail(p, "price or add-on $it is not a whole number of ${coin}-cent pulses")
                }
            }
            return Result.success(
                LaundryMachineConfig(
                    productId = p.id, name = p.name, machineNo = no, kind = kind, priceCents = p.price_cents,
                    relay = relay, coinCents = coin, pulseMs = pulseMs, gapMs = gapMs,
                    durationMin = int("duration_min"), input = int("input")?.takeIf { it in 1..MAX_RELAY },
                    sizeLb = int("size_lb"), singlePulses = single, temps = temps, addons = addons,
                    dryMinutes = dryMinutes, drySteps = drySteps,
                )
            )
        }

        /** [{"name":..,"add_cents":..}] at [key]; null when not set (use the defaults). */
        private fun options(a: JsonObject?, key: String): List<Option>? {
            val arr = a?.get(key) as? JsonArray ?: return null
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Option(name, o["add_cents"]?.jsonPrimitive?.intOrNull ?: 0)
            }
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
