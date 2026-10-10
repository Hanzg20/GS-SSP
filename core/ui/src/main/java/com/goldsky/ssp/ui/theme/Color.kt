package com.goldsky.ssp.ui.theme

import androidx.compose.ui.graphics.Color

// GoldSky palette. The only place hex colours live for the attended-POS UI
// (Iris, Ourea's shared retail screens): screens take colours from
// MaterialTheme.colorScheme and GsTheme.colors, never from here directly.

// Brand
val BrandCyan = Color(0xFF00E5FF)
val BrandBlue = Color(0xFF0066FF)

// Dark (CYBER): navy layers, lightest on top
val Navy950 = Color(0xFF0B0F19)   // page background
val Navy900 = Color(0xFF0F172A)   // bars (top / bottom)
val Navy800 = Color(0xFF162032)   // cards
val Navy700 = Color(0xFF1E293B)   // dialogs, raised controls
val Navy600 = Color(0xFF26354F)   // outlines, dividers
val Slate100 = Color(0xFFF1F5F9)  // primary text on navy
val Slate400 = Color(0xFF94A3B8)  // secondary text on navy

// Light (CLASSIC)
val Paper = Color(0xFFF5F7FA)     // page background
val PaperWhite = Color(0xFFFFFFFF)     // bars, cards, dialogs
val Mist = Color(0xFFEEF2F7)      // raised controls (keys, chips)
val Line = Color(0xFFD0D7E2)      // outlines, dividers
val Ink = Color(0xFF0F172A)       // primary text
val InkMuted = Color(0xFF475569)  // secondary text

// Status, per theme (each pair keeps >= 4.5:1 on its own background)
val SuccessOnDark = Color(0xFF34D399)
val SuccessOnLight = Color(0xFF2E7D32)
val WarningOnDark = Color(0xFFFFB300)
val WarningOnLight = Color(0xFFB26A00)
val DangerOnDark = Color(0xFFFF6B6B)
val DangerOnLight = Color(0xFFD32F2F)
