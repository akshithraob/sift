package com.leakproof.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF00372F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFA4F5E6),
    secondary = Color(0xFFB4BBFF),
    onSecondary = Color(0xFF1B1F7A),
    secondaryContainer = Color(0xFF2E3399),
    onSecondaryContainer = Color(0xFFDDE0FF),
    tertiary = Color(0xFFFFC56B),
    onTertiary = Color(0xFF402D00),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF080D10),
    surfaceContainerLow = Color(0xFF0E161A),
    surfaceContainer = Color(0xFF142026),
    surfaceContainerHigh = Color(0xFF1A282E),
    surfaceContainerHighest = Color(0xFF22323A),
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = Color(0xFFFF6B63),
    onError = Color(0xFF410002)
)

private val LightColorScheme = lightColorScheme(
    primary = BrandPrimaryVariant,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF5EC),
    onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF4F5BD5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E3FF),
    onSecondaryContainer = Color(0xFF0E1470),
    tertiary = Color(0xFFB26A00),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = BrandInk,
    surface = LightSurface,
    onSurface = BrandInk,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFB),
    surfaceContainer = Color(0xFFF0F3F5),
    surfaceContainerHigh = Color(0xFFE9EEF0),
    surfaceContainerHighest = Color(0xFFE2E8EB),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = Color(0xFFD93A32),
    onError = Color.White
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** [themeMode]: 0 follows the system, 1 is always light, 2 is always dark. */
@Composable
fun LeakproofTheme(
    themeMode: Int = 0,
    dynamicColor: Boolean = false, // The Leakproof palette is part of the brand
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
