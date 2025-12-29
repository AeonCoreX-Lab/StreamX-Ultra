package com.aeoncorex.streamx.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// --- আপডেট করা থিমের নাম ---
enum class ThemeName { 
    ULTRA_VIOLET, OCEANIC_BLUE, CRIMSON_RED, 
    CYBER_PUNK, NEON_MATRIX, DEEP_SPACE 
}

@Immutable
data class AppTheme(
    val name: ThemeName,
    val colorScheme: ColorScheme
)

// বিদ্যমান থিমগুলো...
val UltraVioletTheme = AppTheme(
    name = ThemeName.ULTRA_VIOLET,
    colorScheme = darkColorScheme(
        primary = Color(0xFF8B5CF6),
        secondary = Color(0xFF00BCD4),
        background = Color(0xFF020617),
        surface = Color(0xFF1E293B)
    )
)

val OceanicBlueTheme = AppTheme(
    name = ThemeName.OCEANIC_BLUE,
    colorScheme = darkColorScheme(
        primary = Color(0xFF38BDF8),
        secondary = Color(0xFF34D399),
        background = Color(0xFF0B1120),
        surface = Color(0xFF1E293B)
    )
)

val CrimsonRedTheme = AppTheme(
    name = ThemeName.CRIMSON_RED,
    colorScheme = darkColorScheme(
        primary = Color(0xFFF43F5E),
        secondary = Color(0xFFFBBF24),
        background = Color(0xFF180A0A),
        surface = Color(0xFF2d1a1a)
    )
)

// --- নতুন ফিউচারিস্টিক থিমসমূহ ---

val CyberPunkTheme = AppTheme(
    name = ThemeName.CYBER_PUNK,
    colorScheme = darkColorScheme(
        primary = Color(0xFFFF00FF), // Neon Pink
        secondary = Color(0xFF00FFFF), // Electric Cyan
        background = Color(0xFF0D0221), // Midnight Purple
        surface = Color(0xFF261447)
    )
)

val NeonMatrixTheme = AppTheme(
    name = ThemeName.NEON_MATRIX,
    colorScheme = darkColorScheme(
        primary = Color(0xFF00FF41), // Matrix Green
        secondary = Color(0xFF003B00),
        background = Color(0xFF0D0208), 
        surface = Color(0xFF101110)
    )
)

val DeepSpaceTheme = AppTheme(
    name = ThemeName.DEEP_SPACE,
    colorScheme = darkColorScheme(
        primary = Color(0xFFE2E8F0), // Stellar White
        secondary = Color(0xFF64748B),
        background = Color(0xFF020617), 
        surface = Color(0xFF0F172A)
    )
)

// থিম লিস্টে নতুন গুলো যোগ করা হলো
val themes = listOf(
    UltraVioletTheme, OceanicBlueTheme, CrimsonRedTheme, 
    CyberPunkTheme, NeonMatrixTheme, DeepSpaceTheme
)

@Composable
fun StreamXUltraTheme(
    appTheme: AppTheme = UltraVioletTheme,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = appTheme.colorScheme,
        typography = Typography, //
        content = content
    )
}
