package com.goldsky.ssp.feature.apex

import android.content.Context
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.db.ProductEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Reads the local catalog: call off the main thread (Room refuses it there, and the match then silently finds nothing). */
object DeliveryOrderParser {

    private val intentRegex = Regex(
        """(送货单|送货|外卖单|送单|建送单|外卖|配送|外送|delivery\s*order)""",
        RegexOption.IGNORE_CASE
    )

    fun isDeliveryIntent(text: String): Boolean = intentRegex.containsMatchIn(text)

    fun createEmptyProposal(): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("delivery_order"))
        put("customer_name", JsonPrimitive(""))
        put("customer_phone", JsonPrimitive(""))
        put("address", JsonPrimitive(""))
        put("note", JsonPrimitive(""))
        put("items", buildJsonArray { })
        put("check", buildJsonArray {
            add(JsonPrimitive("customer_name"))
            add(JsonPrimitive("address"))
            add(JsonPrimitive("items"))
        })
    }

    /**
     * Parses delivery order fields from spoken or written natural language text.
     */
    fun parseFromText(text: String, context: Context? = null): JsonObject {
        var raw = text.trim()

        // 1. Phone extraction
        var phone = ""
        val phoneMatch = Regex("""(?:电话|手机|phone)?[:：]?\s*(\b1[3-9]\d{9}\b|\b\d{3}[-.]?\d{3}[-.]?\d{4}\b|\b\d{7,11}\b)""").find(raw)
        if (phoneMatch != null) {
            phone = phoneMatch.groupValues[1]
            raw = raw.replace(phoneMatch.value, " ")
        }

        // 2. Customer name extraction
        var customer = ""
        val customerMatch = Regex("""(?:客户|收件人|姓名|买家|给|name)[:：]?\s*([\u4e00-\u9fa5a-zA-Z0-9·]{2,15})""").find(raw)
        if (customerMatch != null) {
            customer = customerMatch.groupValues[1]
            raw = raw.replace(customerMatch.value, " ")
        }

        // 3. Address extraction
        var address = ""
        val addressMatch = Regex("""(?:送到|地址|送至|去|送往|address)[:：]?\s*([^\s,，;；]{3,40})""").find(raw)
        if (addressMatch != null) {
            address = addressMatch.groupValues[1]
            raw = raw.replace(addressMatch.value, " ")
        }

        // 4. Note extraction
        var note = ""
        val noteMatch = Regex("""(?:备注|留言|note)[:：]?\s*([^\s,，;；]{2,40})""").find(raw)
        if (noteMatch != null) {
            note = noteMatch.groupValues[1]
            raw = raw.replace(noteMatch.value, " ")
        }

        // 5. Items extraction
        val catalog: List<ProductEntity> = if (context != null) {
            try {
                LocalDatabase.getInstance(context).productDao().getAll()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val parsedItems = mutableListOf<ParsedItem>()
        val itemPattern = Regex("""([a-zA-Z0-9\u4e00-\u9fa5\s]{1,20}?)\s*(\d+)\s*(?:杯|瓶|份|个|盒|包|件|袋|把|条|斤|两|碗|罐|支|箱|x|pcs|cups?)?""")
        val matches = itemPattern.findAll(raw)

        for (m in matches) {
            val itemName = m.groupValues[1].replace(Regex("""(送货单|生成|建|发|要|买|订|单|配送)"""), "").trim()
            val qty = m.groupValues[2].toIntOrNull() ?: 1
            if (itemName.isNotBlank() && itemName.length >= 2 && itemName != "客户" && itemName != "地址" && itemName != "电话") {
                // Look up in store catalog
                val matchedProduct = catalog.firstOrNull {
                    it.name.contains(itemName, ignoreCase = true) || itemName.contains(it.name, ignoreCase = true)
                }
                val unitPriceCents = matchedProduct?.priceCents ?: 0
                val productId = matchedProduct?.id
                val taxRateBps = matchedProduct?.taxRateBps ?: 0
                val finalName = matchedProduct?.name ?: itemName
                parsedItems.add(ParsedItem(finalName, qty, unitPriceCents, productId, taxRateBps))
            }
        }

        val check = mutableListOf<String>()
        if (customer.isBlank()) check.add("customer_name")
        if (address.isBlank()) check.add("address")
        if (parsedItems.isEmpty()) check.add("items")

        return buildJsonObject {
            put("kind", JsonPrimitive("delivery_order"))
            put("customer_name", JsonPrimitive(customer))
            put("customer_phone", JsonPrimitive(phone))
            put("address", JsonPrimitive(address))
            put("note", JsonPrimitive(note))
            put("items", buildJsonArray {
                parsedItems.forEach { item ->
                    add(buildJsonObject {
                        put("name", JsonPrimitive(item.name))
                        put("quantity", JsonPrimitive(item.quantity))
                        put("unit_price_cents", JsonPrimitive(item.unitPriceCents))
                        if (item.productId != null) put("product_id", JsonPrimitive(item.productId))
                        put("tax_rate_bps", JsonPrimitive(item.taxRateBps))
                    })
                }
            })
            put("check", buildJsonArray {
                check.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    /**
     * A delivery order proposal from apex-assistant (items: name, quantity,
     * unitPrice in dollars or null) with each item matched against this store's
     * catalog -- same name match as [parseFromText] -- so price, tax rate and
     * product come from the catalog, not the model. Items still without a price
     * are flagged ("items") for the manager to fill in on the card.
     */
    fun withCatalogPrices(proposal: JsonObject, context: Context): JsonObject {
        val catalog = try { LocalDatabase.getInstance(context).productDao().getAll() } catch (e: Exception) { emptyList() }
        val items = (proposal["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        var missingPrice = false
        val priced = buildJsonArray {
            items.forEach { it ->
                val name = (it["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
                val qty = (it["quantity"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val spoken = (it["unitPrice"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                val match = catalog.firstOrNull { p -> name.isNotBlank() && (p.name.contains(name, ignoreCase = true) || name.contains(p.name, ignoreCase = true)) }
                val cents = match?.priceCents ?: spoken?.let { Math.round(it * 100).toInt() } ?: 0
                if (cents <= 0) missingPrice = true
                add(buildJsonObject {
                    put("name", JsonPrimitive(match?.name ?: name))
                    put("quantity", JsonPrimitive(qty))
                    put("unit_price_cents", JsonPrimitive(cents))
                    if (match != null) put("product_id", JsonPrimitive(match.id))
                    put("tax_rate_bps", JsonPrimitive(match?.taxRateBps ?: 0))
                })
            }
        }
        val check = (proposal["check"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toMutableSet()
        if (missingPrice) check.add("items")
        return JsonObject(proposal + mapOf("items" to priced, "check" to JsonArray(check.map { JsonPrimitive(it) })))
    }

    data class ParsedItem(
        val name: String,
        val quantity: Int,
        val unitPriceCents: Int,
        val productId: String? = null,
        val taxRateBps: Int = 0
    )
}
