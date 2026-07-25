package com.goldsky.carwash.model

import kotlinx.serialization.Serializable

@Serializable
data class WashPackage(
    val id: String,
    val label: String,
    val price_cents: Int,
    val duration_sec: Int,
    val serial_hex: String
)

@Serializable
data class AppConfig(
    val version: String,
    val org_id: String? = null,
    val vertical_type: String = "WASH",
    val products: List<Product> = emptyList(),
    val settings: KioskSettings = KioskSettings(),
    val branding: Branding = Branding()
)

@Serializable
data class Branding(
    val logo_url: String? = null,
    val brand_name: String = "GS-SSP",
    val primary_color_hex: String = "#FFB800"
)

@Serializable
data class KioskSettings(
    val maintenance_pin: String = "1234",
    val kiosk_timeout_sec: Int = 60,
    val telemetry_interval_sec: Int = 900,
    val pulse_weight_cents: Int = 25, // Default $0.25 per pulse
    val pulse_hex: String = "AA 01 01 55", // Command for 1 pulse
    val locale_tag: String = "en-US", // For dynamic TTS localization
    val print_receipt_enabled: Boolean = false, // Cloud-configurable; some sites run without paper loaded
    val payment_method_mode: Int = PaymentMethodMode.ALL, // 0=ALL, 1=CARD_ONLY, 2=SCAN_ONLY -- see PaymentMethodMode
    // Device-level dispense config (see com.goldsky.carwash.dispense.DispenseEngine).
    // "pulse_credit" (default) | "single_command" | "mdb_vend"
    val dispense_protocol: String = "pulse_credit",
    // "framed_ack" (default, waits for the [0xBB]..[0xEE] reply frame) | "assumed_success" (older boards with no feedback)
    val dispense_ack_mode: String = "framed_ack"
)

/**
 * Values for KioskSettings.payment_method_mode. When only one method is
 * enabled, the "select payment method" dialog is skipped entirely and the
 * terminal goes straight into that flow -- not just hides one button on it.
 */
object PaymentMethodMode {
    const val ALL = 0
    const val CARD_ONLY = 1
    const val SCAN_ONLY = 2
}

@Serializable
data class AdMedia(
    val id: String,
    val media_url: String,
    val media_type: String, // "VIDEO" or "IMAGE"
    val md5_hash: String
)
