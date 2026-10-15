package com.mindfilter.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfilter.app.data.AppSettings
import com.mindfilter.app.data.TAXONOMY
import com.mindfilter.app.data.Theme
import com.mindfilter.app.data.TaxonomyIndex
import com.mindfilter.app.ui.GlassCard
import com.mindfilter.app.ui.IconBubble
import com.mindfilter.app.ui.PillButton
import com.mindfilter.app.ui.ScreenTitle
import com.mindfilter.app.ui.categoryLook
import com.mindfilter.app.ui.theme.LocalGlass

/** État d'une case à trois états selon les thèmes cochés. */
fun triState(themes: List<Theme>, enabled: Set<String>): ToggleableState {
    val n = themes.count { it.id in enabled }
    return when (n) {
        0 -> ToggleableState.Off
        themes.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

/** Coché ou partiel → tout décocher ; décoché → tout cocher. */
fun toggleGroup(themes: List<Theme>, state: ToggleableState) {
    AppSettings.setThemes(themes.map { it.id }, state == ToggleableState.Off)
}

@Composable
private fun checkboxColors() = LocalGlass.current.let { g ->
    CheckboxDefaults.colors(checkedColor = g.accent, uncheckedColor = g.textSecondary, checkmarkColor = g.onAccent)
}

@Composable
fun CategoriesScreen(onOpen: (String) -> Unit) {
    val g = LocalGlass.current
    val s by AppSettings.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Catégories", "Cochez les sujets que vous ne voulez plus voir.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Tout cocher", Modifier.weight(1f), filled = false) {
                    AppSettings.setThemes(TaxonomyIndex.allThemeIds, true)
                }
                PillButton("Tout décocher", Modifier.weight(1f), filled = false) {
                    AppSettings.setThemes(TaxonomyIndex.allThemeIds, false)
                }
            }
        }
        items(TAXONOMY, key = { it.id }) { cat ->
            val look = categoryLook(cat.id)
            val themes = cat.allThemes
            val state = triState(themes, s.enabledThemes)
            val count = themes.count { it.id in s.enabledThemes }
            GlassCard(onClick = { onOpen(cat.id) }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(look.icon, look.tint, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cat.name, color = g.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "$count/${themes.size} thèmes · ${cat.subCategories.size} sous-catégories",
                            color = g.textSecondary, fontSize = 13.sp,
                        )
                    }
                    TriStateCheckbox(state = state, onClick = { toggleGroup(themes, state) }, colors = checkboxColors())
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Détails", tint = g.textSecondary)
                }
            }
        }
    }
}

@Composable
fun CategoryDetailScreen(categoryId: String, onBack: () -> Unit) {
    val g = LocalGlass.current
    val s by AppSettings.state.collectAsStateWithLifecycle()
    val cat = TaxonomyIndex.categoryById[categoryId] ?: run { onBack(); return }
    val look = categoryLook(cat.id)
    val catState = triState(cat.allThemes, s.enabledThemes)

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = g.accent)
                }
                Text("Catégories", color = g.accent, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onBack))
            }
        }
        item {
            GlassCard(padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(look.icon, look.tint, 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cat.name, color = g.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(cat.description, color = g.textSecondary, fontSize = 13.sp)
                    }
                    TriStateCheckbox(state = catState, onClick = { toggleGroup(cat.allThemes, catState) }, colors = checkboxColors())
                }
            }
        }
        items(cat.subCategories, key = { it.id }) { sub ->
            val subState = triState(sub.themes, s.enabledThemes)
            GlassCard(padding = 6.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable { toggleGroup(sub.themes, subState) }
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(sub.name, color = g.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TriStateCheckbox(state = subState, onClick = { toggleGroup(sub.themes, subState) }, colors = checkboxColors())
                }
                HorizontalDivider(color = g.chip, modifier = Modifier.padding(horizontal = 12.dp))
                sub.themes.forEach { theme ->
                    val on = theme.id in s.enabledThemes
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { AppSettings.setThemes(listOf(theme.id), !on) }
                            .padding(start = 24.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(theme.name, color = g.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Checkbox(checked = on, onCheckedChange = { AppSettings.setThemes(listOf(theme.id), it) }, colors = checkboxColors())
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
