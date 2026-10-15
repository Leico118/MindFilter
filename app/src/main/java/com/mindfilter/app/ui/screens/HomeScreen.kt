package com.mindfilter.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import com.mindfilter.app.data.CustomFilter
import com.mindfilter.app.data.RestrictionLevels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfilter.app.data.AppSettings
import com.mindfilter.app.data.ServiceStatus
import com.mindfilter.app.data.TaxonomyIndex
import com.mindfilter.app.data.TAXONOMY
import com.mindfilter.app.engine.KeywordClassifier
import com.mindfilter.app.ui.GlassCard
import com.mindfilter.app.ui.IconBubble
import com.mindfilter.app.ui.Permissions
import com.mindfilter.app.ui.PillButton
import com.mindfilter.app.ui.ScreenTitle
import com.mindfilter.app.ui.SectionTitle
import com.mindfilter.app.ui.theme.LocalGlass

@Composable
fun HomeScreen(resumeTick: Int, onOpenCategories: () -> Unit, onOpenApps: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val s by AppSettings.state.collectAsStateWithLifecycle()
    val connected by ServiceStatus.connected.collectAsStateWithLifecycle()
    val maskedSession by ServiceStatus.maskedThisSession.collectAsStateWithLifecycle()
    val serviceEnabled = remember(resumeTick, connected) { Permissions.isAccessibilityEnabled(ctx) || connected }

    val active = s.protectionEnabled && serviceEnabled
    val statusColor by animateColorAsState(
        when {
            !serviceEnabled -> g.warning
            s.protectionEnabled -> g.positive
            else -> g.textSecondary
        },
        label = "status",
    )

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("MindFilter", "Vos fils d'actualité, sans ce qui vous angoisse.") }

        item {
            GlassCard(padding = 20.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (serviceEnabled) Icons.Filled.Shield else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                !serviceEnabled -> "Service à activer"
                                active -> "Protection active"
                                else -> "Protection en pause"
                            },
                            color = g.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when {
                                !serviceEnabled -> "Autorisez MindFilter dans l'accessibilité Android."
                                active -> "Les contenus choisis sont masqués."
                                else -> "Aucun contenu n'est masqué."
                            },
                            color = g.textSecondary, fontSize = 14.sp,
                        )
                    }
                    Switch(
                        checked = s.protectionEnabled,
                        onCheckedChange = { AppSettings.setProtection(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = g.positive, checkedThumbColor = Color.White),
                    )
                }
                if (!serviceEnabled) {
                    Spacer(Modifier.height(16.dp))
                    PillButton("Activer le service", Modifier.fillMaxWidth()) { Permissions.openAccessibilitySettings(ctx) }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("${s.activeCategoryCount}/${TAXONOMY.size}", "catégories actives", Modifier.weight(1f), onOpenCategories)
                StatCard("${s.monitoredPackages.size}", "applis surveillées", Modifier.weight(1f), onOpenApps)
                StatCard("$maskedSession", "masqués depuis l'activation", Modifier.weight(1f), null)
            }
        }

        item { SectionTitle("Autorisations Android") }
        item {
            GlassCard {
                PermissionRow(
                    icon = Icons.Filled.Accessibility,
                    title = "Service d'accessibilité",
                    ok = serviceEnabled,
                    detail = if (serviceEnabled) "Activé" else "Désactivé — nécessaire pour lire et masquer les publications",
                )
                Spacer(Modifier.height(12.dp))
                PermissionRow(
                    icon = Icons.Filled.Lock,
                    title = "Paramètres restreints (Android 13+)",
                    ok = serviceEnabled,
                    detail = if (serviceEnabled) "Rien à faire" else
                        "Si l'option est grisée : Infos sur l'appli → menu ⋮ → « Autoriser les paramètres restreints »",
                )
                if (!serviceEnabled) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Accessibilité", Modifier.weight(1f)) { Permissions.openAccessibilitySettings(ctx) }
                        PillButton("Infos sur l'appli", Modifier.weight(1f), filled = false) { Permissions.openAppDetails(ctx) }
                    }
                }
            }
        }

        item { SectionTitle("Vérifier une publication") }
        item { EngineTester() }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier, onClick: (() -> Unit)?) {
    val g = LocalGlass.current
    GlassCard(modifier = modifier, corner = 24.dp, padding = 14.dp, onClick = onClick) {
        Text(value, color = g.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = g.textSecondary, fontSize = 12.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, ok: Boolean, detail: String) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, if (ok) g.positive else g.warning, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = g.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = g.textSecondary, fontSize = 13.sp)
        }
        if (ok) Icon(Icons.Filled.CheckCircle, contentDescription = "OK", tint = g.positive, modifier = Modifier.size(22.dp))
    }
}

/** Mots trop courants pour servir de mots-clés. */
private val STOP_WORDS = setOf(
    "avec", "dans", "pour", "mais", "sans", "sous", "leur", "leurs", "cette", "cela", "elle", "elles",
    "nous", "vous", "ils", "sont", "était", "etait", "avait", "avoir", "être", "etre", "fait", "faire",
    "plus", "moins", "très", "tres", "tout", "tous", "toute", "toutes", "comme", "quand", "depuis",
    "après", "apres", "avant", "entre", "aussi", "encore", "alors", "donc", "parce", "chez", "votre",
    "notre", "même", "meme", "quel", "quelle", "quels", "quelles", "dont", "ainsi", "jamais", "toujours",
)

/** Propose jusqu'à 4 mots significatifs d'un texte pour créer un filtre. */
private fun suggestKeywords(text: String): List<String> =
    text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}'’-]+"))
        .map { it.trim('\'', '’', '-') }
        .filter { it.length >= 5 && it !in STOP_WORDS }
        .distinct()
        .sortedByDescending { it.length }
        .take(4)

/**
 * « Vérifier une publication » : collez le texte d'une publication pour savoir si
 * MindFilter la masquerait, POURQUOI, et créer un filtre personnalisé en un geste.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EngineTester() {
    val g = LocalGlass.current
    val s by AppSettings.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    val enabledThemes = remember(s.enabledThemes) { s.enabledThemes.mapNotNull { TaxonomyIndex.themesById[it] } }
    val classifier = remember(s.enabledThemes, s.customFilters, s.restrictionLevel, s.exceptions) {
        KeywordClassifier(enabledThemes, s.customFilters, s.restrictionLevel, s.exceptions)
    }
    val allThemesSameLevel = remember(s.restrictionLevel) {
        KeywordClassifier(TaxonomyIndex.themesById.values, emptyList(), s.restrictionLevel)
    }
    val enabledStrict = remember(s.enabledThemes) {
        KeywordClassifier(enabledThemes, emptyList(), RestrictionLevels.MAX - 1)
    }

    val trimmed = text.trim()
    val result = remember(trimmed, classifier) { if (trimmed.length < 8) null else classifier.classify(trimmed) }
    // Explication quand le texte n'est PAS masqué.
    val explanation: String? = remember(trimmed, result, classifier, allThemesSameLevel, enabledStrict) {
        when {
            trimmed.length < 8 -> null
            result != null -> null
            else -> {
                val other = allThemesSameLevel.classify(trimmed)
                val strict = enabledStrict.classify(trimmed)
                val exception = classifier.exceptionHit(trimmed)
                when {
                    exception != null && (other != null || strict != null) ->
                        "Votre exception « $exception » (onglet Filtres) empêche le masquage."
                    other != null -> "Ce texte parle de « ${other.label} », mais ce thème n'est pas coché dans Catégories."
                    strict != null && s.restrictionLevel < RestrictionLevels.MAX - 1 ->
                        "Proche de « ${strict.label} », mais pas assez net pour votre niveau de filtrage (${RestrictionLevels.name(s.restrictionLevel)}). Il serait masqué avec un niveau plus élevé."
                    else -> "Aucun de vos thèmes ne correspond à ce texte."
                }
            }
        }
    }

    GlassCard(modifier = Modifier.bringIntoViewRequester(bringIntoView)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Filled.Science, g.accent, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Collez ou tapez le texte d'une publication", color = g.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Pour savoir si elle serait masquée, et pourquoi.", color = g.textSecondary, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { f ->
                    if (f.isFocused) scope.launch {
                        delay(350) // le temps que le clavier s'ouvre
                        bringIntoView.bringIntoView()
                    }
                },
            placeholder = { Text("Ex. : Attentat déjoué à Lyon, deux suspects interpellés", color = g.textSecondary) },
            shape = RoundedCornerShape(16.dp),
            minLines = 2,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = g.text,
                unfocusedTextColor = g.text,
                focusedBorderColor = g.accent,
                unfocusedBorderColor = g.chip,
            ),
        )
        if (trimmed.length >= 8) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (result != null) Color(0xEB0B0B0D) else g.chip)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (result != null) {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Serait masquée · ${result.label}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = g.positive, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Serait affichée normalement", color = g.text, fontSize = 14.sp)
                }
            }
            if (explanation != null) {
                Spacer(Modifier.height(6.dp))
                Text(explanation, color = g.textSecondary, fontSize = 13.sp)
            }
            if (result == null) {
                Spacer(Modifier.height(12.dp))
                PillButton("Masquer les publications avec ces mots", Modifier.fillMaxWidth()) { showDialog = true }
            }
        }
    }

    if (showDialog) {
        val kws = suggestKeywords(trimmed)
        FilterDialog(
            initial = CustomFilter(name = kws.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Mon filtre", keywords = kws),
            isNew = true,
            onDismiss = { showDialog = false },
            onSave = {
                AppSettings.upsertFilter(it)
                showDialog = false
                text = ""
            },
        )
    }
}
