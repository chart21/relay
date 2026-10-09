package dev.relay.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Claude-app look: warm paper background, terracotta accent, serif titles. Fixed palette (no wallpaper colours). */
private val Clay = Color(0xFFD97757)
private val ClayDeep = Color(0xFFC15F3C)

private val Light = lightColorScheme(
    primary = ClayDeep, onPrimary = Color.White, primaryContainer = Color(0xFFF3DDD3), onPrimaryContainer = Color(0xFF3D1E12),
    secondary = Color(0xFF6B6A63), onSecondary = Color.White, secondaryContainer = Color(0xFFEDEAE0), onSecondaryContainer = Color(0xFF2B2A26),
    tertiary = Color(0xFF5B7F5E), onTertiary = Color.White, tertiaryContainer = Color(0xFFDDE8DA), onTertiaryContainer = Color(0xFF1B2D1D),
    background = Color(0xFFFAF9F5), onBackground = Color(0xFF1F1E1B),
    surface = Color(0xFFFAF9F5), onSurface = Color(0xFF1F1E1B),
    surfaceVariant = Color(0xFFF0EEE6), onSurfaceVariant = Color(0xFF6B6A63),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F3EC), surfaceContainer = Color(0xFFF0EEE6),
    surfaceContainerHigh = Color(0xFFEBE8DF), surfaceContainerHighest = Color(0xFFE5E2D8),
    outline = Color(0xFF9B9990), outlineVariant = Color(0xFFE2DFD4),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF7DDD8), onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Clay, onPrimary = Color(0xFF2B1209), primaryContainer = Color(0xFF4A2A1D), onPrimaryContainer = Color(0xFFF3DDD3),
    secondary = Color(0xFFB8B6AC), onSecondary = Color(0xFF262624), secondaryContainer = Color(0xFF3A3935), onSecondaryContainer = Color(0xFFE8E6DC),
    tertiary = Color(0xFF9CC29F), onTertiary = Color(0xFF12230F), tertiaryContainer = Color(0xFF2F4430), onTertiaryContainer = Color(0xFFDDE8DA),
    background = Color(0xFF262624), onBackground = Color(0xFFF0EEE6),
    surface = Color(0xFF262624), onSurface = Color(0xFFF0EEE6),
    surfaceVariant = Color(0xFF30302E), onSurfaceVariant = Color(0xFFB8B6AC),
    surfaceContainerLowest = Color(0xFF1F1E1D), surfaceContainerLow = Color(0xFF2B2B29), surfaceContainer = Color(0xFF30302E),
    surfaceContainerHigh = Color(0xFF383836), surfaceContainerHighest = Color(0xFF41413E),
    outline = Color(0xFF8A887F), outlineVariant = Color(0xFF3F3E3A),
    error = Color(0xFFF2B8B5), errorContainer = Color(0xFF5C2A27), onErrorContainer = Color(0xFFF9DEDC),
)

private val base = Typography()
private val serif = FontFamily.Serif
private val typography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontFamily = serif, fontWeight = FontWeight.Normal),
    headlineMedium = base.headlineMedium.copy(fontFamily = serif, fontWeight = FontWeight.Normal),
    headlineSmall = base.headlineSmall.copy(fontFamily = serif, fontWeight = FontWeight.Normal),
    titleLarge = base.titleLarge.copy(fontFamily = serif, fontWeight = FontWeight.Normal),
    titleMedium = base.titleMedium.copy(fontFamily = serif, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
)

/** Text style of assistant replies in the chat: serif like the Claude app. */
val ReplyStyle = TextStyle(fontFamily = serif, fontSize = 16.sp, lineHeight = 24.sp)

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = typography, content = content)
}
