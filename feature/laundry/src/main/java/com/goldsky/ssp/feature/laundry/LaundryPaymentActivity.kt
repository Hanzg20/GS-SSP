package com.goldsky.ssp.feature.laundry

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.DeviceAdapter
import com.goldsky.ssp.common.TtsManager
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DeviceAccessManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.payment.SupabaseClientProvider
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.modbus.ModbusRelayBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Aegis Laundry: one terminal, up to 8 coin-op machines on an RS485 Modbus relay module.
 * Prototype stage: a plain launcher activity (not HOME, no boot start). With no LAUNDRY
 * products set up for the store it runs in demo mode -- sample machines, no card, nothing
 * recorded -- for showing customers.
 */
class LaundryPaymentActivity : ComponentActivity() {

    private val vm: LaundryViewModel by viewModels()
    private var deviceSn = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        TtsManager.registerLifecycle(this, this)
        DeviceRepository.init(this)
        DeviceAccessManager.init(this)

        val vendor = DeviceAdapter.getRecommendedVendor()
        DeviceRepository.persistHardwareVendor(vendor)
        val hardware = runCatching { HardwareFactory.getHardwareProvider(vendor).also { it.init(this) } }.getOrNull()
        val payment = runCatching { PaymentProviderFactory.getPaymentProvider(this, vendor) }.getOrNull()
        deviceSn = runCatching { hardware?.getSerialNumber(this) }.getOrNull().orEmpty()
        DeviceRepository.persistDeviceSn(deviceSn)

        // The relay module: 9600 8N1 is the factory default of the common Modbus relay boards.
        val serial = runCatching { HardwareFactory.getSerialProvider(this, vendor) }.getOrNull()
        val starter: MachineStarter = if (serial != null) ModbusStarter(ModbusRelayBoard(serial)) else DemoStarter
        lifecycleScope.launch(Dispatchers.IO) {
            if (serial != null && serial.open(this@LaundryPaymentActivity)) serial.configure(9600)
            else Log.w(TAG, "Relay module serial port not available")
        }
        vm.attach(payment, starter, deviceSn)

        lifecycleScope.launch { vm.applyConfig(ConfigManager.loadLocalConfig(this@LaundryPaymentActivity)) }
        lifecycleScope.launch {
            SupabaseClientProvider.ensureAuthenticated()
            // No registerDevice while a prototype: on a test terminal the device row belongs
            // to the app the terminal really runs (its app_version must stay that app's).
            if (deviceSn.isNotEmpty()) {
                val identity = DeviceRepository.syncDeviceIdentity(deviceSn)
                DeviceAccessManager.applyActiveState(identity?.is_active)
                vm.setLocked(DeviceAccessManager.isLocked())
                vm.applyConfig(ConfigManager.loadConfig(this@LaundryPaymentActivity, identity?.org_id ?: DeviceRepository.getPersistedOrgId()))
            }
        }

        setContent {
            val s by vm.state.collectAsState()
            BackHandler(enabled = true) { vm.back() } // kiosk: back never leaves the app
            LaundryApp(
                s = s,
                onSelect = vm::select,
                onBack = vm::back,
                onPay = vm::confirm,
                onCancelPayment = vm::cancelPayment,
                onTechTrigger = {},
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    companion object { private const val TAG = "AegisLaundry" }
}
