package com.goldsky.ssp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.TtsManager

/**
 * Entry point for Retail / Attended POS product flavor.
 * Designed for handheld terminals (e.g., A920) or tablets.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Init Core SSP Services
        DeviceRepository.init(this)
        TtsManager.registerLifecycle(this, this)
        
        // TODO: Load Retail UI (Product List, Cart, Staff Login)
    }
}
