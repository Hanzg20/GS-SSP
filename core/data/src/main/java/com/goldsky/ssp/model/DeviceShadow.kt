package com.goldsky.ssp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ShadowState(
    val brightness: Int? = null,
    val volume: Int? = null,
    val is_simulation: Boolean? = null,
    val last_sync_at: String? = null,
    val inventory: Map<String, Int>? = null
)

@Serializable
data class DeviceShadow(
    val device_sn: String,
    val desired: JsonObject? = null,
    val reported: JsonObject? = null,
    val version: Int = 1
)
