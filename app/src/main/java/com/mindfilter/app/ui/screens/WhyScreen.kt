package com.mindfilter.app.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfilter.app.data.AppSettings
import com.mindfilter.app.service.MaskLog
import com.mindfilter.app.ui.GlassCard
import com.mindfilter.app.ui.PillButton
import com.mindfilter.app.ui.navBarSpace
import com.mindfilter.app.ui.theme.LocalGlass
import com.mindfilter.app.vision.ImageAllowList
import com.mindfilter.app.vision.LearnedExamples
import kotlinx.coroutines.delay

/**
 * « Pourquoi c'est masqué ? » : les derniers masquages et leur raison, avec de quoi corriger
 * une erreur en un appui. Rien ne quitte le téléphone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhyScreen(onBack: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val entries by MaskLog.entries.collectAsStateWithLifecycle()
    val corrected by MaskLog.corrected.collectAsStateWithLifecycle()
    val s by AppSettings.state.collectAsStateWithLifecycle()
    BackHandler { onBack() }
    var allowCount by remember { mutableIntStateOf(ImageAllowList.count(app)) }

    // Rafraîchit « il y a 2 min » toutes les 30 secondes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBarSpace()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Retour",
                    tint = g.text,
                    modifier = Modifier.size(28.dp).clickable { onBack() },
                )
                Spacer(Modifier.width(12.dp))
                Text("Pourquoi c'est masqué ?", color = g.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Text(
                "Les derniers masquages et ce qui les a déclenchés. Si un masquage est une erreur, corrigez-le ici : " +
                    "la correction s'applique tout de suite. Seuls le thème et les mots repérés sont gardés (jamais le texte " +
                    "des publications ni les images), et la liste s'efface au redémarrage du téléphone.",
                color = g.textSecondary, fontSize = 13.sp,
            )
        }
        if (entries.isEmpty()) {
            item {
                GlassCard {
                    Text("Rien n'a été masqué pour l'instant.", color = g.text, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Quand un contenu est masqué dans une appli, il apparaît ici avec sa raison.",
                        color = g.textSecondary, fontSize = 13.sp,
                    )
                }
            }
        }
        items(entries, key = { it.id }) { e ->
            val done = e.id in corrected
            GlassCard {
                Text(
                    "${ago(now, e.time)} · ${appName(app, e.app)}",
                    color = g.textSecondary, fontSize = 12.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(e.theme.ifEmpty { "Thème inconnu" }, color = g.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val why = when {
                    e.kind == MaskLog.Kind.TEXT && e.words.isNotEmpty() ->
                        "Mots repérés : " + e.words.joinToString(", ") { "« $it »" }
                    e.kind == MaskLog.Kind.TEXT -> "Repéré dans le texte de la publication."
                    e.words.isNotEmpty() -> "Image · ${e.detail} : " + e.words.joinToString(", ") { "« $it »" }
                    else -> "Image · ${e.detail.ifEmpty { "analyse de l'image" }}"
                }
                Text(why, color = g.text, fontSize = 14.sp)

                if (done) {
                    Spacer(Modifier.height(10.dp))
                    Text("✓ Corrigé : ce ne sera plus masqué.", color = g.positive, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    return@GlassCard
                }
                Spacer(Modifier.height(10.dp))
                Text("C'est une erreur ?", color = g.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (e.kind == MaskLog.Kind.IMAGE && e.imageHash != null) {
                        PillButton("Cette image n'est pas à masquer") {
                            ImageAllowList.add(app, e.imageHash)
                            // Ressemblance avec les images apprises : on apprend aussi l'exemple
                            // « sans problème », pour les images du même genre.
                            if (e.vector != null && e.detail.startsWith("ressemble")) {
                                LearnedExamples.add(app, "", true, e.vector)
                            }
                            MaskLog.markCorrected(e.id)
                            allowCount = ImageAllowList.count(app)
                        }
                    }
                    for (w in e.words) {
                        val already = s.exceptions.any { it.equals(w, ignoreCase = true) }
                        if (!already) {
                            PillButton("Toujours afficher « $w »", filled = false) {
                                AppSettings.addException(w)
                                MaskLog.markCorrected(e.id)
                            }
                        }
                    }
                }
                if (e.kind == MaskLog.Kind.TEXT) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Les mots « toujours afficher » se retrouvent dans l'onglet Filtres. Si ce thème ne vous concerne pas, " +
                            "décochez-le dans Catégories.",
                        color = g.textSecondary, fontSize = 12.sp,
                    )
                }
            }
        }
        if (entries.isNotEmpty()) {
            item {
                PillButton("Effacer la liste", Modifier.fillMaxWidth(), filled = false) { MaskLog.clear() }
            }
        }
        item {
            GlassCard {
                Text("Un masquage n'a pas de sens ?", color = g.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Appuyez sur le bouton, retournez dans l'appli sur l'écran concerné et ne bougez plus : environ 8 secondes après l'appui, MindFilter enregistre " +
                        "la structure de cet écran dans Téléchargements : les blocs, leurs tailles et ce qui a été masqué. Aucun texte " +
                        "n'est enregistré (chaque texte est remplacé par sa longueur) et rien n'est envoyé : c'est vous qui transmettez le fichier.",
                    color = g.textSecondary, fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                PillButton("Enregistrer la structure du prochain écran", Modifier.fillMaxWidth(), filled = false) {
                    com.mindfilter.app.service.ScreenDump.request()
                    android.widget.Toast.makeText(ctx, "Retournez sur l'écran concerné et attendez une dizaine de secondes", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        if (allowCount > 0) {
            item {
                GlassCard {
                    Text("Images autorisées : $allowCount", color = g.text, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Images signalées « pas à masquer ». Seule une empreinte (un nombre) est gardée, pas l'image.",
                        color = g.textSecondary, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    PillButton("Tout réinitialiser", filled = false) {
                        ImageAllowList.clear(app)
                        allowCount = 0
                    }
                }
            }
        }
    }
}

private fun ago(now: Long, t: Long): String {
    val m = ((now - t) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "À l'instant"
        m < 60 -> "Il y a $m min"
        m < 24 * 60 -> "Il y a ${m / 60} h"
        else -> "Il y a ${m / (24 * 60)} j"
    }
}

private fun appName(ctx: Context, pkg: String): String {
    if (pkg.isEmpty()) return "Appli"
    return try {
        val info = ctx.packageManager.getApplicationInfo(pkg, 0)
        ctx.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }
}
