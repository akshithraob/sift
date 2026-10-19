package com.leakproof.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.leakproof.app.R

/**
 * Inter (SIL Open Font License, bundled in the app, no network). One variable font file serves
 * every weight, so text looks the same on every phone brand.
 */
@OptIn(ExperimentalTextApi::class)
val Inter = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(
            resId = R.font.inter,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w))
        )
    }
)

/** Money lines up in columns when every digit has the same width. */
val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

val Typography = Typography(
    displayLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1).sp, fontFeatureSettings = "tnum"),
    displayMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp, fontFeatureSettings = "tnum"),
    displaySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp, fontFeatureSettings = "tnum"),
    headlineLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)
