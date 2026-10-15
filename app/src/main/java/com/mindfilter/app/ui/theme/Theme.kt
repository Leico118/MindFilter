package com.mindfilter.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.mindfilter.app.data.AccentColor
import com.mindfilter.app.data.ThemeMode

/** Palette « verre liquide » : surfaces translucides teintées par la couleur choisie. */
data class GlassColors(
    val dark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val blobA: Color,
    val blobB: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val glassBorderTop: Color,
    val glassBorderBottom: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color = Color.White,
    val positive: Color,
    val warning: Color,
    val danger: Color,
    val chip: Color,
)

private val LightGlass = GlassColors(
    dark = false,
    bgTop = Color(0xFFF3F5FA),
    bgBottom = Color(0xFFE9E6F4),
    blobA = Color(0x553D8BFF),
    blobB = Color(0x44B07BFF),
    glassTop = Color(0xC7FFFFFF),
    glassBottom = Color(0x8FFFFFFF),
    glassBorderTop = Color(0xF2FFFFFF),
    glassBorderBottom = Color(0x40FFFFFF),
    text = Color(0xFF111217),
    textSecondary = Color(0xFF6B6F7B),
    accent = Color(0xFF0A84FF),
    positive = Color(0xFF30B45A),
    warning = Color(0xFFFF9F0A),
    danger = Color(0xFFFF453A),
    chip = Color(0x14000000),
)

private val DarkGlass = GlassColors(
    dark = true,
    bgTop = Color(0xFF0D0E12),
    bgBottom = Color(0xFF15141F),
    blobA = Color(0x402B6CFF),
    blobB = Color(0x338A4DFF),
    glassTop = Color(0x1FFFFFFF),
    glassBottom = Color(0x0DFFFFFF),
    glassBorderTop = Color(0x38FFFFFF),
    glassBorderBottom = Color(0x0AFFFFFF),
    text = Color(0xFFF4F5F8),
    textSecondary = Color(0xFF9A9EAA),
    accent = Color(0xFF3B9BFF),
    positive = Color(0xFF32D74B),
    warning = Color(0xFFFFB340),
    danger = Color(0xFFFF6961),
    chip = Color(0x1AFFFFFF),
)

val LocalGlass = staticCompositionLocalOf { LightGlass }

@Composable
fun MindFilterTheme(mode: ThemeMode, accentColor: AccentColor = AccentColor.BLUE, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Même rendu « verre » partout ; seule la teinte d'accent (et les halos du fond) change.
    val accent = Color(if (dark) accentColor.dark else accentColor.light)
    val base = if (dark) DarkGlass else LightGlass
    // Le fond prend lui aussi la teinte : léger voile en haut, plus marqué en bas,
    // pour éviter un bas d'écran presque noir.
    val glass = base.copy(
        bgTop = lerp(base.bgTop, accent, if (dark) 0.10f else 0.06f),
        bgBottom = lerp(base.bgBottom, accent, if (dark) 0.24f else 0.16f),
        accent = accent,
        onAccent = Color(if (dark) accentColor.onDark else accentColor.onLight),
        blobA = accent.copy(alpha = if (dark) 0.25f else 0.33f),
        blobB = accent.copy(alpha = if (dark) 0.14f else 0.20f),
    )
    val scheme = if (dark) {
        darkColorScheme(
            primary = glass.accent,
            onPrimary = glass.onAccent,
            background = glass.bgTop,
            surface = Color(0xFF1C1C22),
            onSurface = glass.text,
            onBackground = glass.text,
        )
    } else {
        lightColorScheme(
            primary = glass.accent,
            onPrimary = glass.onAccent,
            background = glass.bgTop,
            surface = Color(0xFFFFFFFF),
            onSurface = glass.text,
            onBackground = glass.text,
        )
    }
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
