package com.goldsky.ssp.vending.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Aegis Vend palette = the VIP page's (core/ui bg_vip_page / bg_vip_tile / gold_accent, owner
// 2026-11-03): near-black with a warm glow, dark tiles, gold for the one thing to do,
// emerald for done. Readable on a small screen in daylight.
val KioskBg = Color(0xFF050506)
val KioskCard = Color(0xFF1D1D21)
val KioskCardEdge = Color(0xFF2E2E34)
val KioskAmber = Color(0xFFFFB800)
val KioskEmerald = Color(0xFF10B981)
val KioskRed = Color(0xFFEF4444)
val KioskCyan = Color(0xFF38BDF8)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF94A3B8)

/** The VIP page's background (bg_vip_page). */
val KioskPage = Brush.verticalGradient(listOf(Color(0xFF17150F), KioskBg))

/** A VIP tile (bg_vip_tile); [best] = the gold-edged one (bg_vip_tile_best). */
fun kioskTile(best: Boolean = false) = Brush.linearGradient(
    if (best) listOf(Color(0xFF2A2414), Color(0xFF14120C)) else listOf(KioskCard, Color(0xFF101012))
)
