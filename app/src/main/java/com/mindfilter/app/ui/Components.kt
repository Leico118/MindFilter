package com.mindfilter.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.Coronavirus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SentimentVeryDissatisfied
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindfilter.app.ui.theme.LocalGlass

/** Fond dégradé avec deux halos colorés, sur lequel les cartes de verre se détachent. */
@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(g.bgTop, g.bgBottom))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(listOf(g.blobA, Color.Transparent), center = Offset(size.width * 0.1f, size.height * 0.12f), radius = size.width * 0.9f),
                radius = size.width * 0.9f,
                center = Offset(size.width * 0.1f, size.height * 0.12f),
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(g.blobB, Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.62f), radius = size.width * 0.85f),
                radius = size.width * 0.85f,
                center = Offset(size.width * 0.95f, size.height * 0.62f),
            )
            // Lueur en bas de l'écran, dans la couleur de l'application
            drawCircle(
                brush = Brush.radialGradient(listOf(g.blobA, Color.Transparent), center = Offset(size.width * 0.3f, size.height * 1.02f), radius = size.width * 1.0f),
                radius = size.width * 1.0f,
                center = Offset(size.width * 0.3f, size.height * 1.02f),
            )
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 28.dp,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(corner)
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(Brush.verticalGradient(listOf(g.glassTop, g.glassBottom)))
        .border(1.dp, Brush.verticalGradient(listOf(g.glassBorderTop, g.glassBorderBottom)), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp),
        color = LocalGlass.current.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    val g = LocalGlass.current
    Column(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 6.dp)) {
        Text(
            title,
            color = g.text,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = if (g.serifTitles) 0.sp else (-0.5).sp,
            fontFamily = if (g.serifTitles) androidx.compose.ui.text.font.FontFamily.Serif else null,
        )
        if (subtitle != null) {
            Text(subtitle, color = g.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Contrôle segmenté façon iOS. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(g.chip)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { opt ->
            val isSel = opt == selected
            val bg by animateColorAsState(
                if (isSel) (if (g.dark) Color(0xFF5A5D68) else Color.White) else Color.Transparent,
                label = "seg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(bg)
                    .clickable { onSelect(opt) }
                    .padding(vertical = 9.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(opt),
                    color = g.text,
                    fontSize = if (options.size > 3) 12.sp else 14.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun IconBubble(icon: ImageVector, tint: Color, size: Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, filled: Boolean = true, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) g.accent else g.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) g.onAccent else g.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

data class CategoryLook(val icon: ImageVector, val tint: Color)

fun categoryLook(id: String): CategoryLook = when (id) {
    "sante" -> CategoryLook(Icons.Filled.LocalHospital, Color(0xFFFF453A))
    "virus" -> CategoryLook(Icons.Filled.Coronavirus, Color(0xFF30B45A))
    "accidents" -> CategoryLook(Icons.Filled.DirectionsCar, Color(0xFFFF9F0A))
    "violence" -> CategoryLook(Icons.Filled.LocalPolice, Color(0xFF5E5CE6))
    "guerre" -> CategoryLook(Icons.Filled.Shield, Color(0xFF8E8E93))
    "catastrophes" -> CategoryLook(Icons.Filled.Thunderstorm, Color(0xFF0A84FF))
    "deces" -> CategoryLook(Icons.Filled.SentimentVeryDissatisfied, Color(0xFFAF52DE))
    "economie" -> CategoryLook(Icons.AutoMirrored.Filled.TrendingDown, Color(0xFFD4A017))
    "climat" -> CategoryLook(Icons.Filled.Public, Color(0xFF32ADE6))
    "adulte" -> CategoryLook(Icons.Filled.Explicit, Color(0xFFFF2D55))
    "horreur" -> CategoryLook(Icons.Filled.NightsStay, Color(0xFF8E44AD))
    else -> CategoryLook(Icons.Filled.Warning, Color(0xFFFF6B3D))
}

/** Aperçu fidèle du masque affiché par-dessus les applications. */
@Composable
fun MaskPreview(dark: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    val base = if (dark) Color(0xFF24262D) else Color(0xFFE4E7EE)
    val sheenTop = if (dark) Color(0x26FFFFFF) else Color(0x8CFFFFFF)
    val sheenBottom = if (dark) Color(0x05FFFFFF) else Color(0x14FFFFFF)
    val borderTop = if (dark) Color(0x40FFFFFF) else Color(0xE6FFFFFF)
    val borderBottom = if (dark) Color(0x0FFFFFFF) else Color(0x59FFFFFF)
    Box(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(shape)
            .background(base)
            .background(Brush.verticalGradient(listOf(sheenTop, sheenBottom)))
            .border(1.dp, Brush.linearGradient(listOf(borderTop, borderBottom)), shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(Color(0xEB0B0B0D))
                .padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Masqué", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Jauge « Niveau de filtrage » à 9 crans (1 ; 1,5 ; 2 … 5), façon réglage de luminosité :
 * on fait glisser ou on touche la piste ; une petite vibration marque chaque cran.
 * Les numéros 1 à 5 sont affichés au-dessus des crans entiers.
 */
@Composable
fun RestrictionGauge(level: Int, onChange: (Int) -> Unit, steps: Int = 9) {
    val g = LocalGlass.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val currentLevel by androidx.compose.runtime.rememberUpdatedState(level)
    val currentOnChange by androidx.compose.runtime.rememberUpdatedState(onChange)

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inset = 16.dp
        val widthDp = maxWidth
        val stepDp = (widthDp - inset * 2) / (steps - 1)
        val density = androidx.compose.ui.platform.LocalDensity.current

        fun levelAt(xPx: Float): Int {
            val insetPx = with(density) { inset.toPx() }
            val stepPx = with(density) { stepDp.toPx() }
            val idx = kotlin.math.round((xPx - insetPx) / stepPx).toInt().coerceIn(0, steps - 1)
            return idx + 1
        }
        fun select(xPx: Float) {
            val l = levelAt(xPx)
            if (l != currentLevel) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                currentOnChange(l)
            }
        }

        Column {
            // Numéros 1 à 5 au-dessus des crans entiers (hauteur libre : jamais coupés)
            Box(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                for (i in 0 until steps step 2) {
                    val n = i / 2 + 1
                    val reached = i + 1 <= level
                    Text(
                        "$n",
                        modifier = Modifier
                            .offset(x = inset + stepDp * i - 14.dp)
                            .width(28.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = if (reached) g.text else g.textSecondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        fontWeight = if ((level + 1) / 2 == n) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .pointerInput(stepDp) { detectTapGestures { select(it.x) } }
                    .pointerInput(stepDp) {
                        detectHorizontalDragGestures(
                            onDragStart = { select(it.x) },
                            onHorizontalDrag = { change, _ -> select(change.position.x) },
                        )
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val insetPx = inset.toPx()
                    val stepPx = stepDp.toPx()
                    val cy = size.height / 2f
                    val trackH = 6.dp.toPx()
                    val thumbX = insetPx + (level - 1) * stepPx
                    // Piste vide
                    drawRoundRect(
                        color = g.chip,
                        topLeft = Offset(insetPx, cy - trackH / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - insetPx * 2, trackH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2),
                    )
                    // Partie remplie, dégradé de la couleur d'accent
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(g.accent.copy(alpha = 0.45f), g.accent),
                            startX = insetPx,
                            endX = thumbX.coerceAtLeast(insetPx + 1f),
                        ),
                        topLeft = Offset(insetPx, cy - trackH / 2),
                        size = androidx.compose.ui.geometry.Size((thumbX - insetPx).coerceAtLeast(trackH), trackH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2),
                    )
                    // Crans
                    for (i in 0 until steps) {
                        val x = insetPx + i * stepPx
                        val numbered = i % 2 == 0
                        val r = if (numbered) 3.5.dp.toPx() else 2.dp.toPx()
                        val c = if (i + 1 <= level) Color.White.copy(alpha = 0.9f) else g.textSecondary.copy(alpha = 0.6f)
                        drawCircle(c, radius = r, center = Offset(x, cy))
                    }
                    // Curseur
                    drawCircle(Color.Black.copy(alpha = 0.12f), radius = 15.dp.toPx(), center = Offset(thumbX, cy + 1.5.dp.toPx()))
                    drawCircle(Color.White, radius = 14.dp.toPx(), center = Offset(thumbX, cy))
                    drawCircle(g.accent, radius = 6.dp.toPx(), center = Offset(thumbX, cy))
                }
            }
        }
    }
}

/** Pastilles de couleur pour choisir la teinte de l'application. */
@Composable
fun AccentPicker(
    selected: com.mindfilter.app.data.AccentColor,
    onSelect: (com.mindfilter.app.data.AccentColor) -> Unit,
    enabled: Boolean = true,
) {
    val g = LocalGlass.current
    val all = com.mindfilter.app.data.AccentColor.entries
    val fade by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else 0.35f, label = "picker")
    Column(
        Modifier.graphicsLayer { alpha = fade },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        all.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { a ->
                    val c = Color(if (g.dark) a.dark else a.light)
                    val isSel = a == selected
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (isSel) c else Color.Transparent, CircleShape)
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(c)
                            .clickable(enabled = enabled) { onSelect(a) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSel) {
                            Icon(
                                androidx.compose.material.icons.Icons.Filled.Check,
                                contentDescription = a.label,
                                tint = Color(if (g.dark) a.onDark else a.onLight),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
