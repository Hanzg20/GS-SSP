package com.goldsky.ssp.model

/**
 * One form per code, so a product is found whichever way it was entered or
 * scanned: spaces/dashes dropped, and a 12-digit UPC-A becomes its 13-digit
 * EAN-13 form (leading 0) -- scanners report the same code either way.
 * Same rule as CMP's canonicalBarcode (retailCatalogService.ts).
 */
object Barcodes {
    fun canonical(raw: String?): String? {
        val s = raw?.trim()?.replace(Regex("[\\s-]"), "").orEmpty()
        if (s.isEmpty()) return null
        return if (s.length == 12 && s.all { it.isDigit() }) "0$s" else s
    }
}
