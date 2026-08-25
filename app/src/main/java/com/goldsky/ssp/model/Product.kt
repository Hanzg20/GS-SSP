package com.goldsky.ssp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Product(
    val id: String,
    val name: String,
    val price_cents: Int,
    val vertical_type: String,
    val barcode: String? = null,
    val image_url: String? = null,
    val modifier_groups: List<ModifierGroup>? = null,
    val attributes: JsonObject? = null,
    val is_active: Boolean = true
)

@Serializable
data class ModifierGroup(
    val id: String,
    val name: String,
    val options: List<ProductModifier>
)

@Serializable
data class ProductModifier(
    val id: String,
    val name: String,
    val price_cents: Int = 0
)
