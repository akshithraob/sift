package com.leakproof.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------- Brand
val BrandPrimary = Color(0xFF00BFA5)          // The Sift teal: fills, accents, the drop mark
val BrandPrimaryVariant = Color(0xFF00796B)   // Readable on white (teal 700)
val BrandSecondary = Color(0xFF26A69A)
val BrandDeep = Color(0xFF00574D)             // Bottom of the hero gradient
val BrandInk = Color(0xFF0D1B20)              // Text on light surfaces

// ---------------------------------------------------------------- Neutrals (light)
val LightBackground = Color(0xFFF4F6F7)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE9EEF0)
val LightOnSurfaceVariant = Color(0xFF4A5A61)
val LightOutline = Color(0xFF8A9AA1)
val LightOutlineVariant = Color(0xFFD3DCE0)

// ---------------------------------------------------------------- Neutrals (dark): teal-tinted near-black, layered
val DarkBackground = Color(0xFF0A1013)
val DarkSurface = Color(0xFF111A1E)
val DarkSurfaceVariant = Color(0xFF1B282D)
val DarkOnSurface = Color(0xFFE7EFF1)
val DarkOnSurfaceVariant = Color(0xFFA5B6BD)
val DarkOutline = Color(0xFF5C7078)
val DarkOutlineVariant = Color(0xFF2A3A41)
val DarkPrimary = Color(0xFF45E0C8)

// Older names still used around the app
val BrandBackgroundDark = DarkBackground
val BrandSurfaceDark = DarkSurface
val BrandSurfaceLight = LightSurface
val BrandBackgroundLight = LightBackground

// ---------------------------------------------------------------- Status
val StatusActive = Color(0xFF2EC27E)
val StatusPaused = Color(0xFFF5A524)
val StatusCancelled = Color(0xFFF0554D)

val ConfidenceHigh = StatusActive
val ConfidenceMedium = StatusPaused
val ConfidenceLow = StatusCancelled

// ---------------------------------------------------------------- Payment rails
val RailUpi = Color(0xFF5B6CFF)
val RailNach = Color(0xFF2F9BFF)
val RailSi = Color(0xFF8B5CF6)
val RailCard = Color(0xFFEC4899)

// ---------------------------------------------------------------- What a payment is for
val KindSubscription = BrandPrimary
val KindEmi = Color(0xFF7C6CFF)
val KindSip = Color(0xFF2F9BFF)
val KindInsurance = Color(0xFFF5A524)
