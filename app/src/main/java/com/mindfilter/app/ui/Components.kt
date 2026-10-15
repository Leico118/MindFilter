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
        Text(title, color = g.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
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
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color.White else g.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
