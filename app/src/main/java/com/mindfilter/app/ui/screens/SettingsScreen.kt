package com.mindfilter.app.ui.screens

import com.mindfilter.app.ui.bottomBarSpace
import com.mindfilter.app.ui.navBarSpace
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.mindfilter.app.update.Updater
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfilter.app.data.AppSettings
import com.mindfilter.app.data.MaskStyle
import com.mindfilter.app.data.RestrictionLevels
import com.mindfilter.app.ui.AccentPicker
import com.mindfilter.app.ui.RestrictionGauge
import com.mindfilter.app.data.ServiceStatus
import com.mindfilter.app.data.ThemeMode
import com.mindfilter.app.data.AppStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.Brush
import com.mindfilter.app.data.TouchMode
import com.mindfilter.app.ui.GlassCard
import com.mindfilter.app.ui.IconBubble
import com.mindfilter.app.ui.MaskPreview
import com.mindfilter.app.ui.Permissions
import com.mindfilter.app.ui.PillButton
import com.mindfilter.app.ui.ScreenTitle
import com.mindfilter.app.ui.Segmented
import com.mindfilter.app.ui.theme.LocalGlass

@Composable
private fun Header(icon: ImageVector, tint: Color, title: String, subtitle: String? = null) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, tint, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = g.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = g.textSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
fun SettingsScreen(resumeTick: Int) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val s by AppSettings.state.collectAsStateWithLifecycle()
    val connected by ServiceStatus.connected.collectAsStateWithLifecycle()
    val serviceEnabled = remember(resumeTick, connected) { Permissions.isAccessibilityEnabled(ctx) || connected }
    val systemDark = isSystemInDarkTheme()

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollToUpdate by com.mindfilter.app.ui.UiRequests.scrollToUpdate.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(scrollToUpdate) {
        if (scrollToUpdate) {
            com.mindfilter.app.ui.UiRequests.scrollToUpdate.value = false
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomBarSpace()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Réglages") }

        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Header(Icons.Filled.PowerSettingsNew, g.positive, "Activation automatique", "Reprendre la protection après un redémarrage du téléphone")
                    }
                    Switch(
                        checked = s.autoActivation,
                        onCheckedChange = { v -> AppSettings.update { it.copy(autoActivation = v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = g.positive, checkedThumbColor = Color.White),
                    )
                }
            }
        }

        item {
            GlassCard {
                Header(
                    Icons.Filled.Speed,
                    g.accent,
                    "Niveau de filtrage",
                    "${RestrictionLevels.name(s.restrictionLevel)} · ${RestrictionLevels.display(s.restrictionLevel)}",
                )
                Spacer(Modifier.height(10.dp))
                RestrictionGauge(s.restrictionLevel, { v -> AppSettings.update { it.copy(restrictionLevel = v) } })
                Row(Modifier.fillMaxWidth()) {
                    Text("Plus souple", color = g.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("Plus strict", color = g.textSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(RestrictionLevels.description(s.restrictionLevel), color = g.textSecondary, fontSize = 13.sp)
            }
        }

        item {
            GlassCard {
                Header(Icons.Filled.Palette, Color(0xFFAF52DE), "Thème", "${s.appStyle.label} · ${s.appStyle.description}")
                Spacer(Modifier.height(12.dp))
                StylePicker(s.appStyle) { v -> AppSettings.update { it.copy(appStyle = v) } }
                if (s.appStyle != AppStyle.CONTRASTE) {
                    Spacer(Modifier.height(16.dp))
                    Text("Mode", color = g.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Segmented(ThemeMode.entries.toList(), s.themeMode, { it.label }) { v -> AppSettings.update { it.copy(themeMode = v) } }
                }

                // Couleur, juste sous les thèmes. Verrouillée par défaut et à chaque retour sur
                // l'écran : impossible de la changer par un appui involontaire en faisant défiler.
                var unlocked by remember { mutableStateOf(false) }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Couleur · ${s.accent.label}",
                        color = g.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (unlocked) g.accent else g.chip)
                            .clickable { unlocked = !unlocked }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            contentDescription = if (unlocked) "Verrouiller" else "Déverrouiller",
                            tint = if (unlocked) g.onAccent else g.text,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (unlocked) "Verrouiller" else "Modifier",
                            color = if (unlocked) g.onAccent else g.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                AccentPicker(s.accent, { a -> AppSettings.update { it.copy(accent = a) } }, enabled = unlocked)
                if (!unlocked) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Appuyez sur « Modifier » pour changer de couleur. Le choix se reverrouille quand vous quittez les réglages.",
                        color = g.textSecondary, fontSize = 12.sp,
                    )
                }
            }
        }

        item {
            GlassCard {
                Header(Icons.Filled.VisibilityOff, g.accent, "Apparence des masques")
                Spacer(Modifier.height(12.dp))
                Segmented(MaskStyle.entries.toList(), s.maskStyle, { it.label }) { v -> AppSettings.update { it.copy(maskStyle = v) } }
                Spacer(Modifier.height(12.dp))
                val previewDark = when (s.maskStyle) {
                    MaskStyle.AUTO -> systemDark
                    MaskStyle.DARK -> true
                    MaskStyle.LIGHT -> false
                }
                MaskPreview(previewDark)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Les masques sont opaques : Android n'autorise pas une application à flouter l'écran d'une autre application. Le verre dépoli est donc reproduit par un dégradé, et le contenu dessous reste illisible.",
                    color = g.textSecondary, fontSize = 12.sp,
                )
            }
        }

        item {
            GlassCard {
                Header(Icons.Filled.TouchApp, Color(0xFF32ADE6), "Gestes sur les masques")
                Spacer(Modifier.height(12.dp))
                Segmented(TouchMode.entries.toList(), s.touchMode, { it.label }) { v -> AppSettings.update { it.copy(touchMode = v) } }
                Spacer(Modifier.height(8.dp))
                Text(s.touchMode.description, color = g.textSecondary, fontSize = 13.sp)
            }
        }

        item {
            GlassCard {
                Header(
                    Icons.Filled.Accessibility,
                    if (serviceEnabled) g.positive else g.warning,
                    "Autorisations",
                    if (serviceEnabled) "Service d'accessibilité activé" else "Service d'accessibilité désactivé",
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Accessibilité", Modifier.weight(1f)) { Permissions.openAccessibilitySettings(ctx) }
                    PillButton("Infos sur l'appli", Modifier.weight(1f), filled = false) { Permissions.openAppDetails(ctx) }
                }
            }
        }

        item {
            GlassCard {
                Header(Icons.Filled.PrivacyTip, g.positive, "Confidentialité")
                Spacer(Modifier.height(10.dp))
                listOf(
                    "Toute l'analyse a lieu sur votre téléphone.",
                    "Internet ne sert qu'à vérifier les mises à jour (demande de la dernière version à GitHub) : aucun texte analysé n'est jamais envoyé.",
                    "Aucun texte, aucune conversation, aucune capture d'écran n'est enregistré.",
                    "Les champs de saisie et les mots de passe ne sont jamais lus.",
                    "Pas de compte, pas de publicité, pas de suivi.",
                ).forEach {
                    Text("•  $it", color = g.text, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                }
            }
        }


        item {
            GlassCard {
                Header(Icons.Filled.Lightbulb, g.accent, "Guide de démarrage", "Revoir les étapes pour bien commencer")
                Spacer(Modifier.height(12.dp))
                PillButton("Revoir le guide", Modifier.fillMaxWidth(), filled = false) { AppSettings.restartTutorial() }
            }
        }

        item { UpdateCard() }
    }
}

/** Choix du thème : trois vignettes côte à côte avec un aperçu des couleurs, comme dans Radar. */
@Composable
private fun StylePicker(selected: AppStyle, onSelect: (AppStyle) -> Unit) {
    val g = LocalGlass.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppStyle.entries.forEach { st ->
            val isSel = st == selected
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(g.chip)
                    .border(2.dp, if (isSel) g.accent else Color.Transparent, shape)
                    .clickable { onSelect(st) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(listOf(Color(st.previewTop), Color(st.previewBottom))),
                        )
                        .border(1.dp, g.glassBorderTop, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSel) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Thème choisi",
                            tint = g.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    st.label,
                    color = if (isSel) g.text else g.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun UpdateCard() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsStateWithLifecycle()
    val current = remember { Updater.currentVersion(ctx) }

    GlassCard {
        val (tint, subtitle) = when (val u = st) {
            is Updater.State.UpToDate -> g.positive to "MindFilter $current · à jour ✓"
            is Updater.State.Available -> g.warning to "Version $current · nouvelle version ${u.version} disponible"
            is Updater.State.Downloading -> g.accent to "Téléchargement de la version ${u.version}…"
            is Updater.State.ReadyToInstall -> g.positive to "Version ${u.version} prête à installer"
            else -> g.textSecondary to "MindFilter $current"
        }
        Header(Icons.Filled.SystemUpdate, tint, "Mise à jour", subtitle)
        Spacer(Modifier.height(12.dp))
        when (val u = st) {
            is Updater.State.Idle, is Updater.State.UpToDate -> {
                PillButton("Vérifier les mises à jour", Modifier.fillMaxWidth()) { scope.launch { Updater.check(ctx) } }
            }
            is Updater.State.Checking -> {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = g.accent, trackColor = g.chip)
                Spacer(Modifier.height(8.dp))
                Text("Recherche de la dernière version…", color = g.textSecondary, fontSize = 13.sp)
            }
            is Updater.State.Available -> {
                PillButton("Télécharger la version ${u.version}", Modifier.fillMaxWidth()) {
                    scope.launch { Updater.download(ctx, u.version, u.apkUrl) }
                }
            }
            is Updater.State.Downloading -> {
                LinearProgressIndicator(
                    progress = { u.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = g.accent,
                    trackColor = g.chip,
                )
                Spacer(Modifier.height(8.dp))
                Text("${(u.progress * 100).toInt()} %", color = g.textSecondary, fontSize = 13.sp)
            }
            is Updater.State.ReadyToInstall -> {
                val allowed = remember(st) { mutableStateOf(Updater.canInstall(ctx)) }
                PillButton("Installer la mise à jour", Modifier.fillMaxWidth()) {
                    if (Updater.canInstall(ctx)) Updater.install(ctx)
                    else { allowed.value = false; Updater.openInstallPermission(ctx) }
                }
                if (!allowed.value) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Android va vous demander d'autoriser MindFilter à installer des applications : activez l'option, revenez ici, puis appuyez à nouveau sur « Installer la mise à jour ». Cette autorisation n'est demandée qu'une seule fois.",
                        color = g.textSecondary, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Vos réglages sont conservés : la nouvelle version s'installe par-dessus l'ancienne.",
                    color = g.textSecondary, fontSize = 12.sp,
                )
            }
            is Updater.State.Error -> {
                Text(u.message, color = g.warning, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Réessayer", Modifier.weight(1f)) { scope.launch { Updater.check(ctx) } }
                    PillButton("Via le navigateur", Modifier.weight(1f), filled = false) { Updater.openInBrowser(ctx) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        UpdateNotificationRow()
    }
}

/** Interrupteur « Me prévenir des nouvelles versions » (+ autorisation Android 13+). */
@Composable
private fun UpdateNotificationRow() {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(com.mindfilter.app.update.UpdateNotifier.isEnabled(ctx)) }
    var allowed by remember { mutableStateOf(com.mindfilter.app.update.UpdateNotifier.canNotify(ctx)) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> allowed = granted }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Me prévenir des nouvelles versions", color = g.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Une notification quand une mise à jour sort", color = g.textSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = enabled,
            onCheckedChange = { v ->
                enabled = v
                com.mindfilter.app.update.UpdateNotifier.setEnabled(ctx, v)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = g.positive, checkedThumbColor = Color.White),
        )
    }
    if (enabled && !allowed && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        Spacer(Modifier.height(8.dp))
        Text("Les notifications de MindFilter sont bloquées par Android.", color = g.warning, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        PillButton("Autoriser les notifications", Modifier.fillMaxWidth(), filled = false) {
            if (com.mindfilter.app.update.UpdateNotifier.askedOnce(ctx)) {
                // Déjà refusé une fois : Android ne redemande plus, on ouvre les réglages de l'appli.
                Permissions.openAppDetails(ctx)
            } else {
                com.mindfilter.app.update.UpdateNotifier.markAsked(ctx)
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
