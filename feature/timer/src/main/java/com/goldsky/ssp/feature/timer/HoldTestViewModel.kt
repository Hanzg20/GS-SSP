package com.goldsky.ssp.feature.timer

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.payment.hardware.IGpioProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-site bench test for Aegis Timer's dispense output (time-based products:
 * self-service vacuum, air pump, pet wash, ...), before any
 * customer flow is built on it. Questions it exists to answer on a real
 * Q3mini wired into the machine's existing coin-acceptor harness (first target:
 * the self-service vacuum beside the Eagleson wash):
 *
 * 1. Which line did we land on? The coin acceptor's SIGNAL line (machine's
 *    own timer board counts coin pulses -> use [startPulseTrain], like wash)
 *    or the timer's CONTROL line to the motor/contactor (terminal must hold
 *    the output for the whole session -> [startHold]).
 * 2. For holds: can the ext-board hardware-time 240s/300s in one
 *    triggerRelay call ([HoldMode.RELAY_HW]), or does it wrap/truncate
 *    (~65.5s if the MCU stores ms in 16 bits -- hence the 70s preset)?
 * 3. Does that native call block for the whole duration, and can
 *    [forceOff] (triggerRelayOff) cut a hardware-timed hold short?
 *
 * Every event also goes to logcat under [TAG] so a run can be captured with
 * `adb logcat -s TimerHoldTest WizarPosGpio`.
 */
class HoldTestViewModel : ViewModel() {

    enum class HoldMode(val label: String) {
        RELAY_HW("继电器 PIN6/7 · 硬件计时"),
        RELAY_SW("继电器 PIN6/7 · 程序计时 (On→倒计时→Off)"),
        PULSE_LONG("脉冲 PIN1/2 · 单个长脉冲"),
    }

    enum class Circuit(val label: String) { PULSE("脉冲 PIN1/2"), RELAY("继电器 PIN6/7") }

    data class UiState(
        val vendor: String = "",
        val port: Int = 0,
        val pulseVoltage: Int = 0,
        val runningLabel: String? = null,
        val startedAt: Long = 0L,
        val durationMs: Long = 0L,
        val now: Long = 0L,
        val log: List<String> = emptyList(),
    ) {
        val running get() = runningLabel != null
        val remainingMs get() = if (running) (durationMs - (now - startedAt)).coerceAtLeast(0) else 0L
        val elapsedMs get() = if (running) now - startedAt else 0L
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var gpio: IGpioProvider? = null
    private var job: Job? = null
    private var ticker: Job? = null

    fun attach(provider: IGpioProvider, vendor: String) {
        gpio = provider
        _state.update { it.copy(vendor = vendor) }
        log("GPIO provider attached (vendor=$vendor)")
    }

    fun setPort(port: Int) = _state.update { it.copy(port = port) }
    fun setPulseVoltage(v: Int) = _state.update { it.copy(pulseVoltage = v) }

    fun startHold(mode: HoldMode, durationMs: Long) {
        val g = gpio ?: return log("未取得 GPIO provider")
        if (_state.value.running) return log("已有测试在运行，先 Force OFF 或等待结束")
        val port = _state.value.port
        val voltage = _state.value.pulseVoltage
        begin("${mode.label} · ${durationMs / 1000}s · port $port", durationMs)
        job = viewModelScope.launch(Dispatchers.IO) {
            log("START ${mode.name} port=$port duration=${durationMs}ms" + if (mode == HoldMode.PULSE_LONG) " voltage=$voltage" else "")
            val t0 = SystemClock.elapsedRealtime()
            val ok = when (mode) {
                HoldMode.RELAY_HW -> g.holdRelayOutput(port, durationMs)
                HoldMode.RELAY_SW -> g.setRelay(port, true)
                HoldMode.PULSE_LONG -> g.triggerLogicPulse(port, voltage, durationMs, 100)
            }
            log("native call returned ok=$ok after ${SystemClock.elapsedRealtime() - t0}ms")
            if (!ok) return@launch end("硬件拒绝了指令 (ok=false)")

            val remaining = durationMs - (SystemClock.elapsedRealtime() - t0)
            if (remaining > 0) delay(remaining)
            when (mode) {
                // Deliberately NO software OFF: this mode's whole point is to
                // see whether the board drops the output by itself on time.
                HoldMode.RELAY_HW -> end("倒计时结束 —— 请确认输出是否恰好此刻断开（未发送 OFF）")
                HoldMode.RELAY_SW -> {
                    val off = g.releaseHold(port)
                    end("倒计时结束，已发送 OFF (ok=$off)")
                }
                HoldMode.PULSE_LONG -> end("倒计时结束 —— 请确认脉冲输出是否恰好此刻恢复待机电平")
            }
        }
    }

    fun startPulseTrain(circuit: Circuit, count: Int, widthMs: Long) {
        val g = gpio ?: return log("未取得 GPIO provider")
        if (_state.value.running) return log("已有测试在运行")
        val port = _state.value.port
        val voltage = _state.value.pulseVoltage
        begin("${circuit.label} · $count 个脉冲 · ${widthMs}ms", count * widthMs * 2)
        job = viewModelScope.launch(Dispatchers.IO) {
            log("START pulse train circuit=$circuit port=$port count=$count width=${widthMs}ms" + if (circuit == Circuit.PULSE) " voltage=$voltage" else "")
            for (i in 1..count) {
                val ok = when (circuit) {
                    Circuit.PULSE -> g.triggerLogicPulse(port, voltage, widthMs, widthMs)
                    Circuit.RELAY -> g.triggerRelayPulse(port, widthMs, widthMs)
                }
                if (!ok) return@launch end("第 $i/$count 个脉冲被硬件拒绝")
            }
            end("$count 个脉冲已发送 —— 请看吸尘器计时板是否按投币计数")
        }
    }

    /** Runs on its own coroutine so it isn't queued behind a blocked hold call. */
    fun forceOff() {
        val g = gpio ?: return
        val port = _state.value.port
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val ok = g.releaseHold(port)
            log("FORCE OFF relay port=$port ok=$ok (${SystemClock.elapsedRealtime() - t0}ms) at elapsed=${_state.value.elapsedMs}ms")
        }
        // The hold coroutine may be stuck inside a blocking JNI call that
        // cancel() can't interrupt; this only stops it from logging later.
        job?.cancel()
        stopTicker()
        _state.update { it.copy(runningLabel = null) }
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    private fun begin(label: String, durationMs: Long) {
        val now = SystemClock.elapsedRealtime()
        _state.update { it.copy(runningLabel = label, startedAt = now, durationMs = durationMs, now = now) }
        stopTicker()
        ticker = viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(now = SystemClock.elapsedRealtime()) }
                delay(200)
            }
        }
    }

    private fun end(message: String) {
        log("END: $message")
        stopTicker()
        _state.update { it.copy(runningLabel = null) }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        _state.update { it.copy(log = (listOf("$ts  $message") + it.log).take(200)) }
    }

    override fun onCleared() {
        // Leaving the screen mid-test must not leave the relay latched ON.
        gpio?.releaseHold(_state.value.port)
        super.onCleared()
    }

    companion object {
        const val TAG = "TimerHoldTest"
    }
}
