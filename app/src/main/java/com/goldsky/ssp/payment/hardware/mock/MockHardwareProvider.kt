package com.goldsky.ssp.payment.hardware.mock

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.goldsky.ssp.payment.hardware.*
import kotlinx.coroutines.*

/**
 * Mock implementation of IHardwareProvider for emulator testing.
 */
class MockHardwareProvider : IHardwareProvider {
    private val TAG = "MockHardware"
    
    private val paymentProvider = MockPaymentProvider()
    private val scannerProvider = MockScannerProvider()
    private val serialProvider = MockSerialProvider()
    private val gpioProvider = MockGpioProvider()
    private val mdbProvider = MockMdbProvider()
    private val printerProvider = MockPrinterProvider()

    override fun init(context: Context) {
        Log.i(TAG, "Initialized Mock Hardware Provider")
    }

    override fun registerLifecycle(context: Context, lifecycleOwner: LifecycleOwner) {}

    override fun getSerialNumber(context: Context): String = "MOCK_SN_12345"

    override fun getFirmwareVersion(): String = "MOCK_FW_1.0.0"

    override fun isOperational(): Boolean = true

    override fun setScreenBrightness(percent: Int) {
        Log.d(TAG, "MOCK: Screen brightness set to $percent%")
    }

    override fun getScreenBrightness(): Int = 100

    override fun getScannerProvider(): IScannerProvider = scannerProvider

    override fun getPrinterProvider(): IPrinterProvider = printerProvider

    override fun getSerialProvider(): ISerialProvider = serialProvider

    override fun getGpioProvider(): IGpioProvider = gpioProvider

    override fun getMdbProvider(): IMdbProvider = mdbProvider

    fun getPaymentProvider(): IPaymentProvider = paymentProvider

    override fun feedWatchdog() {
        // Log.v(TAG, "MOCK: Watchdog fed")
    }

    override fun reboot() {
        Log.w(TAG, "MOCK: Reboot triggered")
    }

    override fun getTamperStatus(): Boolean = false

    override fun release() {
        Log.i(TAG, "Mock Hardware released")
    }
}

class MockPaymentProvider : IPaymentProvider {
    override fun startSale(amountInCents: Int, ecrRefNum: String, callback: IPaymentProvider.PaymentCallback) {
        Log.i("MockPayment", "Starting mock sale for $amountInCents cents")
        CoroutineScope(Dispatchers.Main).launch {
            callback.onProgress("MOCK: PROCESSING PAYMENT...")
            delay(1500)
            callback.onSuccess("MOCK_AUTH", "MOCK_REF_" + java.lang.System.currentTimeMillis(), "MOCK_ENTRY")
        }
    }

    override fun voidTransaction(refNum: String, callback: IPaymentProvider.PaymentCallback) {
        callback.onSuccess("VOID_OK", "MOCK_VOID_REF")
    }

    override fun refundTransaction(refNum: String, amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        callback.onSuccess("REFUND_OK", "MOCK_REFUND_REF")
    }

    override fun startCardDetection(amountInCents: Int, callback: IPaymentProvider.PaymentCallback) {
        callback.onProgress("READY")
    }

    override fun stopCardDetection() {}

    override fun cancelCurrentTransaction() {}

    override fun closeBatch(callback: IPaymentProvider.PaymentCallback) {
        callback.onSuccess("BATCH_OK", "MOCK_BATCH")
    }
}

class MockScannerProvider : IScannerProvider {
    override fun startScan(callback: IScannerProvider.ScanCallback) {
        Log.i("MockScanner", "Mock scanning started")
    }
    override fun stopScan() {}
    override fun setScannerLed(enabled: Boolean) {}
}

class MockSerialProvider : ISerialProvider {
    override fun open(context: Context): Boolean = true
    override fun close() {}
    override fun isOpened(): Boolean = true
    override fun sendBytes(data: ByteArray): Boolean = true
    override fun sendHexString(hexStr: String): Boolean = true
    override suspend fun sendCommandWithAck(hexStr: String, timeoutMs: Int, maxRetries: Int): Boolean = true
}

class MockGpioProvider : IGpioProvider {
    override fun setRelay(port: Int, on: Boolean) {
        Log.i("MockGpio", "MOCK: Relay $port set to $on")
    }
    override fun readInput(port: Int): Int = 0
    override fun release() {}
}

class MockMdbProvider : IMdbProvider {
    override fun startPolling(listener: IMdbProvider.MdbEventListener) {
        Log.i("MockMdb", "Mock MDB polling started")
    }
    override fun stopPolling() {}
    override fun approveVend(): Boolean = true
    override fun denyVend(): Boolean = true
}

class MockPrinterProvider : IPrinterProvider {
    override fun init(): Boolean = true
    override fun addText(text: String): Boolean {
        Log.i("MockPrinter", "PRINT: $text")
        return true
    }
    override fun startPrint(): Boolean = true
    override fun feedPaper(lines: Int): Boolean = true
    override fun hasPaper(): Boolean = true
}
