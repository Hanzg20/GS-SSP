package com.goldsky.ssp.feature.timer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goldsky.ssp.payment.TransactionRecord
import com.goldsky.ssp.payment.TransactionRepository
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Technician self-test for the "charged but the machine didn't start" path:
 * a real $1.00 sale immediately followed by the same voidOrRefund() call
 * TimerViewModel (and wash) make after a dispense failure. Exists because
 * that reversal path was broken on WizarPOS (it referenced the RRN instead of
 * the sale's TransIndexCode) and nothing short of a real card exercises it.
 * Both legs are recorded as SELFTEST_* transactions for the audit trail.
 */
class PaymentSelfTestViewModel(app: Application) : AndroidViewModel(app) {

    data class State(val running: Boolean = false, val lines: List<String> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun run(payment: IPaymentProvider, deviceSn: String) {
        if (_state.value.running) return
        val ref = "SELFTEST_${System.currentTimeMillis()}"
        _state.value = State(running = true)
        log("开始：刷卡 $1.00（$ref），成功后立即撤销")
        viewModelScope.launch {
            TransactionRepository.recordTransaction(
                getApplication(),
                TransactionRecord(device_sn = deviceSn, amount = AMOUNT_CENTS, payment_status = "PENDING", ecr_ref_num = ref, payment_method = "CREDIT_CARD"),
            )
            payment.startSale(AMOUNT_CENTS, ref, object : IPaymentProvider.PaymentCallback {
                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    log("收款成功 auth=$authCode refNum=$refNum")
                    viewModelScope.launch { TransactionRepository.updatePaymentStatus(getApplication(), ref, "PAID", entryMode) }
                    log("发起撤销 voidOrRefund(refNum=$refNum)…")
                    payment.voidOrRefund(refNum, AMOUNT_CENTS) { ok, method ->
                        viewModelScope.launch {
                            if (ok) TransactionRepository.updatePaymentStatus(getApplication(), ref, if (method == "REFUND") "REFUNDED" else "VOIDED")
                            finish(if (ok) "✅ 撤销成功（方式：$method）" else "❌ 撤销失败（VOID 和 REFUND 都未成功）—— 请人工处理这笔 $1.00")
                        }
                    }
                }

                override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                    viewModelScope.launch { TransactionRepository.updatePaymentStatus(getApplication(), ref, "DECLINED") }
                    finish("收款未完成：$errorMsg（未扣款，无需撤销）")
                }

                override fun onProgress(message: String) = log(message)
            })
        }
    }

    private fun finish(line: String) {
        log(line)
        _state.update { it.copy(running = false) }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        _state.update { it.copy(lines = (it.lines + line).takeLast(8)) }
    }

    private companion object {
        const val TAG = "TimerPaySelfTest"
        const val AMOUNT_CENTS = 100
    }
}
