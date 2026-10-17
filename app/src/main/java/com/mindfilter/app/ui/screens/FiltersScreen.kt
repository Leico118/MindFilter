package com.mindfilter.app.ui.screens

import com.mindfilter.app.ui.bottomBarSpace
import com.mindfilter.app.ui.navBarSpace
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.text.input.ImeAction
import com.mindfilter.app.data.RestrictionLevels
import com.mindfilter.app.data.TaxonomyIndex
import com.mindfilter.app.engine.KeywordClassifier
import com.mindfilter.app.ui.SectionTitle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfilter.app.data.AppSettings
import com.mindfilter.app.data.CustomFilter
import com.mindfilter.app.data.Sensitivity
import com.mindfilter.app.ui.GlassCard
import com.mindfilter.app.ui.IconBubble
import com.mindfilter.app.ui.PillButton
import com.mindfilter.app.ui.ScreenTitle
import com.mindfilter.app.ui.Segmented
import com.mindfilter.app.ui.theme.LocalGlass

@Composable
fun FiltersScreen() {
    val g = LocalGlass.current
    val s by AppSettings.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CustomFilter?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomBarSpace()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Filtres personnalisés", "Ce que vous voulez masquer, et ce que vous voulez toujours voir.") }
        item { SectionTitle("Masquer") }
        item { PillButton("Nouveau filtre", Modifier.fillMaxWidth()) { creating = true } }
        if (s.customFilters.isEmpty()) {
            item {
                GlassCard {
                    Text("Aucun filtre personnalisé", color = g.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Exemple : un filtre « Série X » avec les mots-clés « série x, spoiler, épisode final » masquera les publications qui en parlent.",
                        color = g.textSecondary, fontSize = 14.sp,
                    )
                }
            }
        }
        items(s.customFilters, key = { it.id }) { f ->
            GlassCard(padding = 14.dp, onClick = { editing = f }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Filled.Tune, g.accent, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = g.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            f.keywords.joinToString(", ") + " · " + f.sensitivity.label,
                            color = g.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = f.enabled,
                        onCheckedChange = { AppSettings.upsertFilter(f.copy(enabled = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = g.positive, checkedThumbColor = Color.White),
                    )
                    IconButton(onClick = { AppSettings.deleteFilter(f.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = g.danger)
                    }
                }
            }
        }
        item { SectionTitle("Toujours afficher") }
        item { ExceptionsCard(s.exceptions) }
    }

    if (creating || editing != null) {
        FilterDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { AppSettings.upsertFilter(it); creating = false; editing = null },
        )
    }
}

@Composable
internal fun FilterDialog(
    initial: CustomFilter?,
    onDismiss: () -> Unit,
    onSave: (CustomFilter) -> Unit,
    isNew: Boolean = initial == null,
) {
    val g = LocalGlass.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var keywords by remember { mutableStateOf(initial?.keywords?.joinToString(", ") ?: "") }
    var sensitivity by remember { mutableStateOf(initial?.sensitivity ?: Sensitivity.NORMAL) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = g.text,
        unfocusedTextColor = g.text,
        focusedBorderColor = g.accent,
        unfocusedBorderColor = g.chip,
    )
    val parsed = keywords.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(if (isNew) "Nouveau filtre" else "Modifier le filtre") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nom du filtre") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keywords, onValueChange = { keywords = it },
                    label = { Text("Mots-clés et expressions") },
                    supportingText = { Text("Séparés par des virgules. Ajoutez * pour inclure les variantes (ex. : incendi*).") },
                    minLines = 3,
                    shape = RoundedCornerShape(14.dp), colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Sensibilité", color = g.textSecondary, fontSize = 13.sp)
                Segmented(listOf(Sensitivity.LIGHT, Sensitivity.NORMAL, Sensitivity.STRICT), sensitivity, { it.label }) { sensitivity = it }
                Text(
                    when (sensitivity) {
                        Sensitivity.VERY_LIGHT, Sensitivity.LIGHT -> "Masque si au moins deux mots-clés apparaissent."
                        Sensitivity.MODERATE, Sensitivity.NORMAL -> "Masque dès qu'un mot-clé apparaît."
                        Sensitivity.STRICT -> "Masque dès qu'un mot-clé ou l'une de ses variantes apparaît."
                    },
                    color = g.textSecondary, fontSize = 13.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed.isNotEmpty(),
                onClick = {
                    val base = initial ?: CustomFilter(name = "", keywords = emptyList())
                    onSave(
                        base.copy(
                            name = name.trim().ifEmpty { parsed.first() },
                            keywords = parsed,
                            sensitivity = sensitivity,
                        ),
                    )
                },
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/**
 * Exceptions : mots ou expressions qui ne sont JAMAIS masqués, même si le thème
 * correspondant est coché. Ex. : « tremblement de terre » avec « Catastrophes » coché.
 */
@Composable
private fun ExceptionsCard(exceptions: List<String>) {
    val g = LocalGlass.current
    var input by remember { mutableStateOf("") }
    val coverage = remember(exceptions) {
        KeywordClassifier(TaxonomyIndex.themesById.values, emptyList(), RestrictionLevels.MAX, exceptions).exceptionCoverage()
    }
    val add = {
        input.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { AppSettings.addException(it) }
        input = ""
    }

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Filled.Visibility, g.positive, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Mots à ne jamais masquer", color = g.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Une publication qui contient l'un de ces mots reste visible, même si son thème est coché.",
                    color = g.textSecondary, fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ex. : tremblement de terre", color = g.textSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = g.text,
                    unfocusedTextColor = g.text,
                    focusedBorderColor = g.accent,
                    unfocusedBorderColor = g.chip,
                ),
            )
            Spacer(Modifier.width(8.dp))
            PillButton("Ajouter", filled = input.isNotBlank()) { add() }
        }
        if (exceptions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            exceptions.forEach { e ->
                val themes = coverage[e].orEmpty()
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e, color = g.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (themes.isEmpty()) "Ne correspond à aucun thème pour l'instant"
                            else "Toujours visible, même avec : " + themes.joinToString(", "),
                            color = if (themes.isEmpty()) g.textSecondary else g.positive,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = { AppSettings.removeException(e) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Retirer", tint = g.textSecondary)
                    }
                }
            }
        }
    }
}
