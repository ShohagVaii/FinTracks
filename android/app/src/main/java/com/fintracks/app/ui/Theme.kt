package com.fintracks.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Accent palettes from the web app (sky / emerald / rose / amber). */
enum class AccentColor(val key: String, val label: String, val darkPrimary: Color, val lightPrimary: Color, val gradientEnd: Color) {
    SKY("sky", "স্কাই", Color(0xFF38BDF8), Color(0xFF0EA5E9), Color(0xFF2563EB)),
    EMERALD("emerald", "এমারেল্ড", Color(0xFF34D399), Color(0xFF10B981), Color(0xFF059669)),
    ROSE("rose", "রোজ", Color(0xFFFB7185), Color(0xFFF43F5E), Color(0xFFE11D48)),
    AMBER("amber", "অ্যাম্বার", Color(0xFFFBBF24), Color(0xFFF59E0B), Color(0xFFD97706));

    companion object {
        fun fromKey(key: String?): AccentColor = entries.firstOrNull { it.key == key } ?: SKY
    }
}

// Tailwind slate scale used by the web app.
val Slate950 = Color(0xFF020617)
val Slate900 = Color(0xFF0F172A)
val Slate800 = Color(0xFF1E293B)
val Slate600 = Color(0xFF475569)
val Slate500 = Color(0xFF64748B)
val Slate400 = Color(0xFF94A3B8)
val Slate200 = Color(0xFFE2E8F0)
val Slate50 = Color(0xFFF8FAFC)
val Sky400 = Color(0xFF38BDF8)
val Sky500 = Color(0xFF0EA5E9)
val Blue600 = Color(0xFF2563EB)

val Emerald500 = Color(0xFF10B981)
val Rose500 = Color(0xFFF43F5E)
val Amber500 = Color(0xFFF59E0B)
val Amber400 = Color(0xFFFBBF24)
val Indigo400 = Color(0xFF818CF8)

@Composable
fun FinTracksTheme(dark: Boolean, accent: AccentColor, content: @Composable () -> Unit) {
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent.darkPrimary,
            onPrimary = Slate950,
            background = Slate950,
            onBackground = Color.White,
            surface = Slate900.copy(alpha = 0.7f),
            onSurface = Color.White,
            surfaceVariant = Slate800,
            onSurfaceVariant = Slate400,
            outline = Color.White.copy(alpha = 0.10f),
            error = Rose500
        )
    } else {
        lightColorScheme(
            primary = accent.lightPrimary,
            onPrimary = Color.White,
            background = Slate50,
            onBackground = Slate900,
            surface = Color.White.copy(alpha = 0.85f),
            onSurface = Slate900,
            surfaceVariant = Slate200,
            onSurfaceVariant = Slate600,
            outline = Color.Black.copy(alpha = 0.08f),
            error = Rose500
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
