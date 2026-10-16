package com.mindfilter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindfilter.app.ui.theme.LocalGlass
import kotlin.math.roundToInt

/** Positions à l'écran des éléments que le guide peut montrer du doigt. */
object TutorialAnchors {
    val rects = mutableStateMapOf<String, Rect>()
}

/** Marque un élément de l'interface pour que le guide puisse pointer dessus. */
fun Modifier.tutorialAnchor(id: String): Modifier =
    onGloballyPositioned { TutorialAnchors.rects[id] = it.boundsInRoot() }

/** Une étape du guide : quel onglet afficher, quel élément montrer, quoi dire. */
data class TutorialStep(val tab: Int?, val anchor: String?, val emoji: String, val title: String, val text: String)

val TUTORIAL_STEPS = listOf(
    TutorialStep(
        tab = 0, anchor = null, emoji = "👋",
        title = "Bienvenue dans MindFilter",
        text = "MindFilter masque les publications angoissantes dans vos applis. En quelques étapes, on le met en route ensemble.",
    ),
    TutorialStep(
        tab = 0, anchor = "home_status", emoji = "🛡️",
        title = "1. Activez le service",
        text = "Appuyez sur « Activer le service » et activez MindFilter. Sans cela, rien ne peut être masqué. Si Android refuse, l'encadré « Ça ne marche pas ? » de cette carte vous explique quoi faire.",
    ),
    TutorialStep(
        tab = 1, anchor = "tab1", emoji = "🗂️",
        title = "2. Choisissez les sujets",
        text = "Dans Catégories, cochez ce que vous ne voulez plus voir : maladies, guerres, accidents… Touchez une catégorie pour affiner.",
    ),
    TutorialStep(
        tab = 2, anchor = "tab2", emoji = "📱",
        title = "3. Choisissez les applis",
        text = "Dans Applis, indiquez où MindFilter doit agir : Facebook, Instagram, Chrome… Les autres applis ne sont jamais lues.",
    ),
    TutorialStep(
        tab = 3, anchor = "tab3", emoji = "✏️",
        title = "4. Vos propres mots",
        text = "Dans Filtres, ajoutez des mots à masquer, ou au contraire des mots à ne jamais masquer.",
    ),
    TutorialStep(
        tab = 4, anchor = "tab4", emoji = "🎨",
        title = "5. À votre goût",
        text = "Dans Réglages : niveau de filtrage, thème, couleur et mises à jour.",
    ),
    TutorialStep(
        tab = 0, anchor = null, emoji = "✨",
        title = "C'est prêt !",
        text = "Vous pouvez revoir ce guide à tout moment depuis Réglages.",
    ),
)

/**
 * Guide de démarrage : un voile léger, l'élément concerné mis en lumière, et une bulle
 * façon bande dessinée reliée à lui par un petit trait et une flèche.
 */
@Composable
fun TutorialOverlay(step: Int, onNext: () -> Unit, onSkip: () -> Unit) {
    val g = LocalGlass.current
    val density = LocalDensity.current
    val s = TUTORIAL_STEPS[step.coerceIn(0, TUTORIAL_STEPS.lastIndex)]
    val last = step >= TUTORIAL_STEPS.lastIndex
    val target = s.anchor?.let { TutorialAnchors.rects[it] }

    BackHandler { onSkip() }

    val appear = remember(step) { Animatable(0f) }
    LaunchedEffect(step) { appear.animateTo(1f, tween(260)) }

    var bubble by remember { mutableStateOf<Rect?>(null) }
    var bubbleHeight by remember { mutableStateOf(0) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Bloque les appuis sur l'appli derrière le guide.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val pad = with(density) { 10.dp.toPx() }
        val gap = with(density) { 56.dp.toPx() }
        val margin = with(density) { 20.dp.toPx() }
        val spot = target?.let { Rect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }

        // Voile + projecteur sur l'élément montré + trait et flèche vers la bulle.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = appear.value },
        ) {
            drawRect(Color.Black.copy(alpha = 0.55f))
            if (spot != null) {
                val radius = CornerRadius(22.dp.toPx())
                drawRoundRect(Color.Transparent, spot.topLeft, spot.size, radius, blendMode = BlendMode.Clear)
                drawRoundRect(g.accent, spot.topLeft, spot.size, radius, style = Stroke(2.5.dp.toPx()))
                val b = bubble
                if (b != null) {
                    val below = b.top > spot.bottom
                    val x = spot.center.x.coerceIn(b.left + 28.dp.toPx(), b.right - 28.dp.toPx())
                    val from = Offset(x, if (below) b.top else b.bottom)
                    val to = Offset(spot.center.x, if (below) spot.bottom + 6.dp.toPx() else spot.top - 6.dp.toPx())
                    val stroke = 3.dp.toPx()
                    // Petit trait (la queue de la bulle)…
                    drawLine(g.accent, from, to, strokeWidth = stroke, cap = StrokeCap.Round)
                    drawCircle(g.accent, 5.dp.toPx(), from)
                    // … terminé par une flèche qui pointe l'élément.
                    val dir = Offset(to.x - from.x, to.y - from.y)
                    val len = kotlin.math.sqrt(dir.x * dir.x + dir.y * dir.y).coerceAtLeast(1f)
                    val ux = dir.x / len
                    val uy = dir.y / len
                    val head = 12.dp.toPx()
                    val arrow = Path().apply {
                        moveTo(to.x, to.y)
                        lineTo(to.x - ux * head - uy * head * 0.6f, to.y - uy * head + ux * head * 0.6f)
                        lineTo(to.x - ux * head + uy * head * 0.6f, to.y - uy * head - ux * head * 0.6f)
                        close()
                    }
                    drawPath(arrow, g.accent)
                }
            }
        }

        // Position de la bulle : au-dessus de l'élément s'il est en bas de l'écran, sinon dessous.
        val bubbleY: Float = when {
            spot == null -> (h - bubbleHeight) / 2f
            spot.center.y > h / 2f -> spot.top - gap - bubbleHeight
            else -> spot.bottom + gap
        }.coerceIn(margin, (h - bubbleHeight - margin).coerceAtLeast(margin))

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, bubbleY.roundToInt()) }
                .padding(horizontal = 20.dp)
                .widthIn(max = 380.dp)
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * 12.dp.toPx()
                }
                .shadow(14.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(g.accent)
                .onGloballyPositioned {
                    bubble = it.boundsInRoot()
                    bubbleHeight = it.size.height
                }
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.emoji, fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Text(s.title, color = g.onAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(s.text, color = g.onAccent, fontSize = 15.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Points de progression
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                    TUTORIAL_STEPS.indices.forEach { i ->
                        Box(
                            Modifier
                                .size(if (i == step) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(g.onAccent.copy(alpha = if (i == step) 1f else 0.4f)),
                        )
                    }
                }
                if (!last) {
                    Text(
                        "Passer",
                        color = g.onAccent.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onSkip() }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (last) "Terminer" else if (step == 0) "C'est parti" else "Suivant",
                    color = g.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(g.onAccent)
                        .clickable { onNext() }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }
}
