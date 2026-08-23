package com.goldsky.ssp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Product(
    val id: String,
    val name: String,
    val price_cents: Int,
    val vertical_type: String, // WASH, VENDING, RETAIL, etc.
    val barcode: String? = null, // For retail scanning
    val image_url: String? = null,
    val attributes: JsonObject? = null, // Stores { "serial_hex": "AA...", "pulse": 12 }
    val is_active: Boolean = true
)
