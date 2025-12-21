package com.aeoncorex.streamx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// --- নতুন: থিমের নাম এবং কালার স্কিম ---
enum class ThemeName { ULTRA_VIOLET, OCEANIC_BLUE, CRIMSON_RED }

@Immutable
data class AppTheme(
    val name: ThemeName,
    val colorScheme: ColorScheme
)

val UltraVioletTheme = AppTheme(
    name = ThemeName.ULTRA_VIOLET,
    colorScheme = darkColorScheme(
        primary = Color(0xFF8B5CF6), // Purple
        secondary = Color(0xFF00BCD4), // Cyan
        background = Color(0xFF020617),
        surface = Color(0xFF1E293B)
    )
)

val OceanicBlueTheme = AppTheme(
    name = ThemeName.OCEANIC_BLUE,
    colorScheme = darkColorScheme(
        primary = Color(0xFF38BDF8), // Light Blue
        secondary = Color(0xFF34D399), // Green
        background = Color(0xFF0B1120),
        surface = Color(0xFF1E293B)
    )
)

val CrimsonRedTheme = AppTheme(
    name = ThemeName.CRIMSON_RED,
    colorScheme = darkColorScheme(
        primary = Color(0xFFF43F5E), // Red
        secondary = Color(0xFFFBBF24), // Amber
        background = Color(0xFF180A0A),
        surface = Color(0xFF2d1a1a)
    )
)

val themes = listOf(UltraVioletTheme, OceanicBlueTheme, CrimsonRedTheme)

@Composable
fun StreamXUltraTheme(
    appTheme: AppTheme = UltraVioletTheme, // ডিফল্ট থিম
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = appTheme.colorScheme,
        typography = Typography,
        content = content
    )
}