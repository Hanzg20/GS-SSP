package com.goldsky.ssp.feature.timer

import android.content.Context
import android.util.Log
import com.goldsky.ssp.payment.hardware.IGpioProvider

/**
 * How this terminal drives the machine it's wired to. Set by the technician
 * on site (TechScreen), stored on the terminal -- the wiring belongs to the
 * terminal, not to the merchant's cloud config.
 */
enum class OutputMode(val label: String, val hint: String) {
    RELAY_HOLD("继电器保持 PIN6/7", "继电器在整个时长内闭合（接设备电源/使能线）"),
    LEVEL_HOLD("电平保持 PIN1", "PIN1 在整个时长内保持有效电平；硬件计时，中途无法提前关断"),
    COIN_PULSES("投币脉冲 PIN1", "按金额发脉冲，模拟投币器（设备自己计时）"),
}

data class OutputSettings(
    val mode: OutputMode = OutputMode.RELAY_HOLD,
    val port: Int = 0,
    /** PIN1/2 idle level, same meaning as the output test's voltage (0 = idle high). */
    val voltage: Int = 0,
    /** COIN_PULSES: value of one pulse; $2 at 100 = 2 pulses, at 25 = 8. */
    val centsPerPulse: Int = 100,
    /** COIN_PULSES: pulse ON time, and the same OFF gap between pulses. */
    val pulseWidthMs: Long = 100,
) {
    fun pulsesFor(priceCents: Int): Int = (priceCents / centsPerPulse.coerceAtLeast(1)).coerceAtLeast(1)
}

class OutputSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("timer_output", Context.MODE_PRIVATE)

    fun load() = OutputSettings(
        mode = runCatching { OutputMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(OutputMode.RELAY_HOLD),
        port = prefs.getInt(KEY_PORT, 0),
        voltage = prefs.getInt(KEY_VOLTAGE, 0),
        centsPerPulse = prefs.getInt(KEY_CENTS, 100),
        pulseWidthMs = prefs.getLong(KEY_WIDTH, 100),
    )

    fun save(s: OutputSettings) {
        prefs.edit()
            .putString(KEY_MODE, s.mode.name)
            .putInt(KEY_PORT, s.port)
            .putInt(KEY_VOLTAGE, s.voltage)
            .putInt(KEY_CENTS, s.centsPerPulse)
            .putLong(KEY_WIDTH, s.pulseWidthMs)
            .apply()
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_PORT = "port"
        const val KEY_VOLTAGE = "voltage"
        const val KEY_CENTS = "cents_per_pulse"
        const val KEY_WIDTH = "pulse_width_ms"
    }
}

/**
 * The customer session's output, following the technician's [OutputSettings]
 * at the moment each session starts.
 */
class ConfigurableOutput(
    private val gpio: IGpioProvider,
    private val settings: () -> OutputSettings,
) : TimerOutput {

    override suspend fun start(durationMs: Long, priceCents: Int): Boolean {
        val s = settings()
        return when (s.mode) {
            OutputMode.RELAY_HOLD -> gpio.holdRelayOutput(s.port, durationMs)
            OutputMode.LEVEL_HOLD -> gpio.triggerLogicPulse(s.port, s.voltage, durationMs, 100)
            OutputMode.COIN_PULSES -> {
                val n = s.pulsesFor(priceCents)
                Log.i(TAG, "Coin pulses: $n x ${s.pulseWidthMs}ms for $priceCents cents on port ${s.port}")
                (1..n).all { gpio.triggerLogicPulse(s.port, s.voltage, s.pulseWidthMs, s.pulseWidthMs) }
            }
        }
    }

    /**
     * Session interrupted by a restart. Hold modes re-issue the remaining time;
     * coin pulses must NOT be sent again -- the machine is already timing the
     * credit it got, and resending would hand out free time.
     */
    override suspend fun resume(remainingMs: Long): Boolean = when (settings().mode) {
        OutputMode.COIN_PULSES -> true.also { Log.i(TAG, "Resume: coin-pulse mode, machine keeps its own time") }
        else -> start(remainingMs, 0)
    }

    override fun stop() {
        if (settings().mode == OutputMode.RELAY_HOLD) gpio.releaseHold(settings().port)
    }

    private companion object { const val TAG = "TimerOutput" }
}
