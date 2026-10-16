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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mindfilter.app.data.AccentColor
import com.mindfilter.app.data.AppStyle
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
    /** Fond de la barre d'onglets (null = rendu verre par défaut). */
    val tabTop: Color? = null,
    val tabBottom: Color? = null,
    /** Titres à empattements (thème Papier). */
    val serifTitles: Boolean = false,
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
fun MindFilterTheme(
    mode: ThemeMode,
    accentColor: AccentColor = AccentColor.BLUE,
    style: AppStyle = AppStyle.VERRE,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val glass = when (style) {
        AppStyle.VERRE -> verreColors(modeDark(mode, systemDark), accentColor)
        AppStyle.NOIR -> if (modeDark(mode, systemDark)) noirColors(accentColor) else sobreClairColors(accentColor)
        AppStyle.CONTRASTE -> contrasteColors(accentColor)
    }
    val dark = glass.dark

    // Icônes de la barre d'état (heure, batterie) lisibles sur le fond du thème.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
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
    CompositionLocalProvider(
        LocalGlass provides glass,
        LocalDensity provides adaptiveDensity(),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Adapte toute l'interface à l'écran du téléphone :
 *  - sur un écran plus étroit que la référence (≈ 400 dp, ex. ROG Phone), tout est
 *    réduit proportionnellement (textes, bulles, cartes, marges) jusqu'à −22 % ;
 *  - la taille de police système est respectée, mais plafonnée pour qu'un réglage
 *    « très grande police » ne fasse pas exploser la mise en page.
 * Sur un grand écran rien n'est agrandi : l'apparence actuelle reste le maximum.
 */
@Composable
private fun adaptiveDensity(): Density {
    val base = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp.takeIf { it > 0 } ?: 400
    val factor = (widthDp / 400f).coerceIn(0.78f, 1f)
    val font = base.fontScale.coerceAtMost(1.10f)
    return Density(density = base.density * factor, fontScale = font)
}

/** Verre : le rendu d'origine de MindFilter, teinté par la couleur choisie. */
private fun verreColors(dark: Boolean, accentColor: AccentColor): GlassColors {
    val accent = Color(if (dark) accentColor.dark else accentColor.light)
    val base = if (dark) DarkGlass else LightGlass
    // Le fond prend lui aussi la teinte : léger voile en haut, plus marqué en bas.
    return base.copy(
        bgTop = lerp(base.bgTop, accent, if (dark) 0.10f else 0.06f),
        bgBottom = lerp(base.bgBottom, accent, if (dark) 0.24f else 0.16f),
        accent = accent,
        onAccent = Color(if (dark) accentColor.onDark else accentColor.onLight),
        blobA = accent.copy(alpha = if (dark) 0.25f else 0.33f),
        blobB = accent.copy(alpha = if (dark) 0.14f else 0.20f),
    )
}

/** Noir : sobre et professionnel, surfaces pleines, pas de halos. */
private fun noirColors(accentColor: AccentColor) = DarkGlass.copy(
    bgTop = Color(0xFF0A0A0C),
    bgBottom = Color(0xFF0A0A0C),
    blobA = Color.Transparent,
    blobB = Color.Transparent,
    glassTop = Color(0xFF141418),
    glassBottom = Color(0xFF141418),
    glassBorderTop = Color(0x12FFFFFF),
    glassBorderBottom = Color(0x12FFFFFF),
    text = Color(0xFFECEBE8),
    textSecondary = Color(0xFF8E8D94),
    accent = Color(accentColor.dark),
    onAccent = Color(accentColor.onDark),
    chip = Color(0xFF1C1C22),
    tabTop = Color(0xF7141418),
    tabBottom = Color(0xF7141418),
)

/** Sobre en mode clair : fond gris très clair, cartes blanches pleines, pas de halos. */
private fun sobreClairColors(accentColor: AccentColor) = LightGlass.copy(
    bgTop = Color(0xFFF2F2F4),
    bgBottom = Color(0xFFF2F2F4),
    blobA = Color.Transparent,
    blobB = Color.Transparent,
    glassTop = Color(0xFFFFFFFF),
    glassBottom = Color(0xFFFFFFFF),
    glassBorderTop = Color(0x14000000),
    glassBorderBottom = Color(0x14000000),
    accent = Color(accentColor.light),
    onAccent = Color(accentColor.onLight),
    chip = Color(0xFFE8E8EC),
    tabTop = Color(0xFAFFFFFF),
    tabBottom = Color(0xFAFFFFFF),
)

private fun modeDark(mode: ThemeMode, systemDark: Boolean) = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Contraste : lisibilité maximale, noir et blanc, avec la couleur choisie. */
private fun contrasteColors(accentColor: AccentColor) = DarkGlass.copy(
    bgTop = Color(0xFF000000),
    bgBottom = Color(0xFF000000),
    blobA = Color.Transparent,
    blobB = Color.Transparent,
    glassTop = Color(0xFF0C0C0C),
    glassBottom = Color(0xFF0C0C0C),
    glassBorderTop = Color(0x66FFFFFF),
    glassBorderBottom = Color(0x66FFFFFF),
    text = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFEDEDED),
    accent = Color(accentColor.dark),
    onAccent = Color(accentColor.onDark),
    positive = Color(0xFF4CD964),
    warning = Color(0xFFFFD60A),
    danger = Color(0xFFFF6B6B),
    chip = Color(0xFF1D1D1D),
    tabTop = Color(0xFF0C0C0C),
    tabBottom = Color(0xFF0C0C0C),
)
