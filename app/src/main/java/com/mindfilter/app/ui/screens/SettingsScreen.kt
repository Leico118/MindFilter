package com.mindfilter.app.ui.screens

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
import androidx.compose.material.icons.filled.Info
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

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
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
                Header(Icons.Filled.Palette, Color(0xFFAF52DE), "Mode clair et sombre", "Apparence de MindFilter")
                Spacer(Modifier.height(12.dp))
                Segmented(ThemeMode.entries.toList(), s.themeMode, { it.label }) { v -> AppSettings.update { it.copy(themeMode = v) } }
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
                    "L'application n'a même pas l'autorisation d'accéder à Internet.",
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
                Header(Icons.Filled.Info, g.textSecondary, "Version de l'application", "MindFilter 0.2.6 · interface adaptée à l’écran")
            }
        }

        item {
            // Verrouillé par défaut et à chaque retour sur l'écran : impossible de changer
            // la couleur par un appui involontaire en faisant défiler les réglages.
            var unlocked by remember { mutableStateOf(false) }
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Header(Icons.Filled.ColorLens, g.accent, "Couleur de l'application", s.accent.label)
                    }
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
                Spacer(Modifier.height(14.dp))
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
    }
}
