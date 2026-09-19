package com.goldsky.ssp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

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
) {
    /**
     * No dedicated `category` column exists in `public.products` (see
     * docs/supabase_full_schema.sql) -- category rides inside the existing
     * flexible `attributes` JSONB instead of a schema migration, same
     * pattern the "Hardware-specific" attributes comment on that table
     * already documents. Null for any product that hasn't been categorized
     * yet; callers should bucket those under an "Other"/uncategorized group
     * rather than hide them.
     */
    val category: String? get() = attributes?.get("category")?.jsonPrimitive?.contentOrNull

    /**
     * Same "no dedicated column, rides in `attributes` JSONB" story as
     * [category]. Null (not 0) means "stock isn't tracked for this product"
     * -- distinct from an actual 0, which means genuinely out of stock.
     * Callers must not treat null as zero.
     */
    val stockQty: Int? get() = attributes?.get("stock_qty")?.jsonPrimitive?.intOrNull
}

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
