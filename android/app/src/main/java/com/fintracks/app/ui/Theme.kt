package com.fintracks.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.fintracks.app.R

/** Accent palettes from the web app (sky / emerald / rose / amber). */
enum class AccentColor(val key: String, private val bnLabel: String, private val enLabel: String, val darkPrimary: Color, val lightPrimary: Color, val gradientEnd: Color) {
    SKY("sky", "স্কাই", "Sky", Color(0xFF38BDF8), Color(0xFF0EA5E9), Color(0xFF2563EB)),
    EMERALD("emerald", "এমারেল্ড", "Emerald", Color(0xFF34D399), Color(0xFF10B981), Color(0xFF059669)),
    ROSE("rose", "রোজ", "Rose", Color(0xFFFB7185), Color(0xFFF43F5E), Color(0xFFE11D48)),
    AMBER("amber", "অ্যাম্বার", "Amber", Color(0xFFFBBF24), Color(0xFFF59E0B), Color(0xFFD97706));

    val label: String get() = tr(bnLabel, enLabel)

    companion object {
        fun fromKey(key: String?): AccentColor = entries.firstOrNull { it.key == key } ?: SKY
    }
}

// Tailwind slate scale used by the web app.
// Colour grading sampled from the FinTrack walkthrough video and screenshots (Drive "FinTrack" folder):
// the dark background is a blue-black (#000010 to #080818), cards lift slightly to #101828 / #181828,
// and the primary accent is a sky blue close to #08A0E8.
val VideoBackground = Color(0xFF020617)
val VideoCardLift = Color(0xFF101828)
val VideoAccent = Color(0xFF08A0E8)
val Slate950 = Color(0xFF020617)
val Slate900 = Color(0xFF0F172A)
val Slate800 = Color(0xFF1E293B)
val Slate600 = Color(0xFF475569)
val Slate500 = Color(0xFF64748B)
val Slate400 = Color(0xFF94A3B8)
val Slate200 = Color(0xFFE2E8F0)
val Slate50 = Color(0xFFF8FAFC)
// These three follow the accent colour chosen in Settings. Sky is the default.
val Sky400: Color get() = AppPrefs.accent.darkPrimary
val Sky500: Color get() = AppPrefs.accent.lightPrimary
val Blue600: Color get() = AppPrefs.accent.gradientEnd

val Emerald500 = Color(0xFF10B981)
val Rose500 = Color(0xFFF43F5E)
val Amber500 = Color(0xFFF59E0B)
val Amber400 = Color(0xFFFBBF24)
val Indigo400 = Color(0xFF818CF8)
val Violet600 = Color(0xFF7C3AED)
val Indigo600 = Color(0xFF4F46E5)

// Web tokens (the web app's Tailwind values). Charts use these fixed colours; income, expense and net keep their own.
val ChartIncome = Emerald500
val ChartExpense = Rose500
val ChartNet = Color(0xFF38BDF8)
/** Glass card: white 3% fill with a white 10% border (dark mode). */
val GlassFill = Color.White.copy(alpha = 0.03f)
val GlassBorder = Color.White.copy(alpha = 0.10f)
/** Pie / category colour order from the web app. */
val PieColors = listOf(Color(0xFF38BDF8), Color(0xFF818CF8), Color(0xFFFBBF24), Color(0xFFF472B6), Color(0xFF34D399), Color(0xFF94A3B8))


/** Hind Siliguri (res/font) — the web app's font. */
val HindFamily = FontFamily(
    Font(R.font.hind_siliguri_light, FontWeight.Light),
    Font(R.font.hind_siliguri_regular, FontWeight.Normal),
    Font(R.font.hind_siliguri_medium, FontWeight.Medium),
    Font(R.font.hind_siliguri_semibold, FontWeight.SemiBold),
    Font(R.font.hind_siliguri_bold, FontWeight.Bold)
)

/** Same styles as the Material defaults, with the Hind family applied to every one. */
private fun Typography.withFamily(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f)
)

@Composable
fun FinTracksTheme(dark: Boolean, accent: AccentColor, content: @Composable () -> Unit) {
    val scheme = if (dark) {
        darkColorScheme(
            primary = if (accent == AccentColor.SKY) VideoAccent else accent.darkPrimary,
            onPrimary = Slate950,
            background = VideoBackground,
            onBackground = Color.White,
            surface = Slate900,
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
    MaterialTheme(colorScheme = scheme, typography = Typography().withFamily(HindFamily), content = content)
}

// Colour literals that used to be scattered through the screens. Moved here so each one has one definition.
val Tok_40FFFFFF = Color(0x40FFFFFF)
val Tok_55FFFFFF = Color(0x55FFFFFF)
val Tok_66888888 = Color(0x66888888)
val Tok_80FFFFFF = Color(0x80FFFFFF)
val Tok_99101828 = Color(0x99101828)
val Tok_CCFFFFFF = Color(0xCCFFFFFF)
val Tok_E6101828 = Color(0xE6101828)
val Tok_FF0B1220 = Color(0xFF0B1220)
val Tok_FF0B1F1C = Color(0xFF0B1F1C)
val Tok_FF0EA5E9 = Color(0xFF0EA5E9)
val Tok_FF0F766E = Color(0xFF0F766E)
val Tok_FF14B8A6 = Color(0xFF14B8A6)
val Tok_FF1E1B4B = Color(0xFF1E1B4B)
val Tok_FF1F2937 = Color(0xFF1F2937)
val Tok_FF2DD4BF = Color(0xFF2DD4BF)
val Tok_FF34D399 = Color(0xFF34D399)
val Tok_FF3730A3 = Color(0xFF3730A3)
val Tok_FF60A5FA = Color(0xFF60A5FA)
val Tok_FF6D5BF5 = Color(0xFF6D5BF5)
val Tok_FF84CC16 = Color(0xFF84CC16)
val Tok_FFA78BFA = Color(0xFFA78BFA)
val Tok_FFC7D2FE = Color(0xFFC7D2FE)
val Tok_FFDB2777 = Color(0xFFDB2777)
val Tok_FFE879F9 = Color(0xFFE879F9)
val Tok_FFFACC15 = Color(0xFFFACC15)
val Tok_FFFB7185 = Color(0xFFFB7185)
val Tok_FFFB923C = Color(0xFFFB923C)
