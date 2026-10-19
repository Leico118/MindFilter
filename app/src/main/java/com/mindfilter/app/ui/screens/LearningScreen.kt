package com.mindfilter.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfilter.app.data.AppSettings
import com.mindfilter.app.data.TAXONOMY
import com.mindfilter.app.data.TaxonomyIndex
import com.mindfilter.app.ui.GlassCard
import com.mindfilter.app.ui.PillButton
import com.mindfilter.app.ui.ScreenTitle
import com.mindfilter.app.ui.SectionTitle
import com.mindfilter.app.ui.navBarSpace
import com.mindfilter.app.ui.theme.LocalGlass
import com.mindfilter.app.vision.ImageEmbedder
import com.mindfilter.app.vision.LearnedExamples
import com.mindfilter.app.vision.ObjectRecognizer
import com.mindfilter.app.vision.TextReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Outils d'analyse, créés une seule fois et utilisés un par un (pas en parallèle). */
private class LearningTools(ctx: Context) {
    val lock = Mutex()
    private val embedderLazy = lazy { ImageEmbedder(ctx) }
    private val objectsLazy = lazy { ObjectRecognizer(ctx) }
    private val readerLazy = lazy { TextReader(ctx) }
    val embedder by embedderLazy
    val objects by objectsLazy
    val reader by readerLazy

    fun close() {
        if (embedderLazy.isInitialized()) embedder.close()
        if (objectsLazy.isInitialized()) objects.close()
        if (readerLazy.isInitialized()) reader.close()
    }
}

/** Charge une image de la galerie en taille raisonnable (max ~1280 px). */
private fun loadBitmap(ctx: Context, uri: Uri): Bitmap? = try {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (opts.outWidth / (sample * 2) >= 1280 || opts.outHeight / (sample * 2) >= 1280) sample *= 2
    val real = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) }
} catch (e: Exception) {
    null
}

private class TestResult(
    val verdict: String,
    val learned: List<Pair<String, Float>>,
    val objects: List<Pair<String, Float>>,
    val text: String,
)

/**
 * Mode développeur : apprendre à MindFilter à reconnaître des images par l'exemple, tester,
 * régler la sensibilité et exporter l'apprentissage (fichier à envoyer pour l'intégrer à une
 * mise à jour). Aucune image n'est conservée : seulement leur empreinte.
 */
@Composable
fun LearningScreen(onBack: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val s by AppSettings.state.collectAsStateWithLifecycle()
    val tools = remember { LearningTools(app) }
    DisposableEffect(Unit) {
        onDispose {
            // Libère les modèles en quittant l'écran (une fois l'éventuelle analyse terminée).
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { tools.lock.withLock { tools.close() } }
        }
    }

    BackHandler { onBack() }

    var refresh by remember { mutableIntStateOf(0) }
    val counts = remember(refresh) { LearnedExamples.counts(app) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var chooseTheme by remember { mutableStateOf(false) }
    var pendingTheme by remember { mutableStateOf<String?>(null) } // "" = sans problème
    var test by remember { mutableStateOf<TestResult?>(null) }
    var threshold by remember { mutableStateOf(LearnedExamples.threshold) }

    fun learn(uris: List<Uri>, theme: String) {
        if (uris.isEmpty()) return
        busy = true
        status = "Analyse de ${uris.size} image(s)…"
        scope.launch {
            val done = withContext(Dispatchers.Default) {
                tools.lock.withLock {
                    if (!tools.embedder.available) return@withLock -1
                    var n = 0
                    for (u in uris) {
                        val bmp = loadBitmap(app, u) ?: continue
                        val v = tools.embedder.embed(bmp)
                        bmp.recycle()
                        if (v != null) {
                            LearnedExamples.add(app, theme, safe = theme.isEmpty(), vector = v)
                            n++
                        }
                    }
                    n
                }
            }
            busy = false
            refresh++
            status = when {
                done < 0 -> "Le modèle d'empreintes est absent de cette version : impossible d'apprendre."
                theme.isEmpty() -> "$done image(s) apprise(s) comme « sans problème »."
                else -> "$done image(s) apprise(s) pour « ${TaxonomyIndex.themeName(theme)} »."
            }
        }
    }

    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        val t = pendingTheme
        pendingTheme = null
        if (t != null) learn(uris, t)
    }

    val pickTest = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "Test en cours…"
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                tools.lock.withLock {
                    val bmp = loadBitmap(app, uri) ?: return@withLock null
                    val enabled = AppSettings.state.value.enabledThemes
                    val v = if (tools.embedder.available) tools.embedder.embed(bmp) else null
                    val learned = v?.let { LearnedExamples.scores(it).take(4) }.orEmpty()
                    val match = v?.let { LearnedExamples.match(it, enabled) }
                    val objs = if (tools.objects.available) tools.objects.recognize(bmp, 4) else emptyList()
                    val text = if (tools.reader.available) tools.reader.read(bmp) else ""
                    bmp.recycle()
                    val verdict = when {
                        match != null -> "Masquée : ressemble à « ${TaxonomyIndex.themeName(match.theme)} » (${(match.similarity * 100).toInt()} %)"
                        v == null -> "Modèle d'empreintes absent : seul le texte et les objets sont analysés."
                        else -> "Pas masquée par l'apprentissage."
                    }
                    TestResult(verdict, learned, objs, text)
                }
            }
            busy = false
            status = null
            test = r
        }
    }

    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(LearnedExamples.exportJson(app).toByteArray()) }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            status = if (ok) "Fichier exporté. Envoie-le à Claude pour l'intégrer à une mise à jour." else "Export impossible."
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
                Spacer(Modifier.width(8.dp))
                ScreenTitle("Apprentissage", "Mode développeur")
            }
        }

        if (!s.imageAnalysis) {
            item {
                GlassCard {
                    Text(
                        "L'option « Analyse des images » est désactivée dans Réglages : ce qui est appris ici ne servira au masquage qu'une fois l'option activée.",
                        color = g.warning, fontSize = 13.sp,
                    )
                }
            }
        }

        item {
            GlassCard {
                Text("Apprendre par l'exemple", color = g.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Choisis un thème puis des images de ta galerie qui le montrent : MindFilter retient leur empreinte et masquera les images qui leur ressemblent. " +
                        "Ajoute aussi des images « sans problème » qui ressemblent mais ne doivent PAS être masquées : ça évite les erreurs. " +
                        "Plus il y a d'exemples (20 ou plus par thème), mieux c'est.",
                    color = g.textSecondary, fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                PillButton("Images à masquer…", Modifier.fillMaxWidth()) { if (!busy) chooseTheme = true }
                Spacer(Modifier.height(8.dp))
                PillButton("Images sans problème…", Modifier.fillMaxWidth(), filled = false) {
                    if (!busy) {
                        pendingTheme = ""
                        pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                status?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = g.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item { SectionTitle("Ce qui a été appris") }
        item {
            GlassCard {
                if (counts.isEmpty()) {
                    Text("Rien pour l'instant.", color = g.textSecondary, fontSize = 13.sp)
                } else {
                    counts.forEach { (theme, c) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (theme.isEmpty()) "Sans problème" else TaxonomyIndex.themeName(theme),
                                    color = g.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${c.first} ajoutée(s) ici" + if (c.second > 0) " · ${c.second} intégrée(s) à l'appli" else "",
                                    color = g.textSecondary, fontSize = 12.sp,
                                )
                            }
                            if (c.first > 0) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Effacer",
                                    tint = g.textSecondary,
                                    modifier = Modifier.size(22.dp).clickable {
                                        LearnedExamples.clearLocal(app, theme)
                                        refresh++
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        item { SectionTitle("Tester une image") }
        item {
            GlassCard {
                Text(
                    "Choisis une image : MindFilter indique s'il la masquerait, à quoi elle ressemble, les objets reconnus et le texte lu dedans.",
                    color = g.textSecondary, fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                PillButton("Tester une image…", Modifier.fillMaxWidth(), filled = false) {
                    if (!busy) pickTest.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                test?.let { t ->
                    Spacer(Modifier.height(12.dp))
                    Text(t.verdict, color = g.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (t.learned.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Ressemblance avec ce qui a été appris :", color = g.textSecondary, fontSize = 12.sp)
                        t.learned.forEach { (theme, sim) ->
                            Text(
                                "• ${if (theme.isEmpty()) "Sans problème" else TaxonomyIndex.themeName(theme)} : ${(sim * 100).toInt()} %",
                                color = g.text, fontSize = 13.sp,
                            )
                        }
                    }
                    if (t.objects.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Objets reconnus :", color = g.textSecondary, fontSize = 12.sp)
                        Text(
                            t.objects.joinToString(" · ") { (n, p) -> "$n ${(p * 100).toInt()} %" },
                            color = g.text, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Texte lu dans l'image :", color = g.textSecondary, fontSize = 12.sp)
                    Text(t.text.ifBlank { "(aucun)" }.take(300), color = g.text, fontSize = 13.sp)
                }
            }
        }

        item { SectionTitle("Sensibilité") }
        item {
            GlassCard {
                Text(
                    "Ressemblance minimale pour masquer : ${(threshold * 100).toInt()} %",
                    color = g.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Plus bas = masque davantage (mais plus d'erreurs). Plus haut = seulement les images très proches des exemples.",
                    color = g.textSecondary, fontSize = 12.sp,
                )
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = { LearnedExamples.setThreshold(app, threshold) },
                    valueRange = 0.60f..0.95f,
                    colors = SliderDefaults.colors(thumbColor = g.accent, activeTrackColor = g.accent),
                )
            }
        }

        item { SectionTitle("Partager l'apprentissage") }
        item {
            GlassCard {
                Text(
                    "Crée un fichier avec ce que tu as appris ici (empreintes uniquement, aucune image). Envoie-le à Claude : il l'intégrera à la prochaine mise à jour pour que tous les téléphones en profitent.",
                    color = g.textSecondary, fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                PillButton("Exporter le fichier d'apprentissage", Modifier.fillMaxWidth()) {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date())
                    exportFile.launch("mindfilter-apprentissage-$date.json")
                }
            }
        }
    }

    if (chooseTheme) {
        ThemeChooser(
            onDismiss = { chooseTheme = false },
            onChosen = { theme ->
                chooseTheme = false
                pendingTheme = theme
                pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
}

/** Choix du thème en deux temps : catégorie, puis thème. */
@Composable
private fun ThemeChooser(onDismiss: () -> Unit, onChosen: (String) -> Unit) {
    val g = LocalGlass.current
    var category by remember { mutableStateOf<String?>(null) }
    val cat = category?.let { id -> TAXONOMY.firstOrNull { it.id == id } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cat?.name ?: "Que montrent ces images ?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (cat == null) {
                    items(TAXONOMY) { c ->
                        Text(
                            c.name,
                            color = g.text,
                            fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable { category = c.id }.padding(vertical = 10.dp),
                        )
                    }
                } else {
                    cat.subCategories.forEach { sub ->
                        item {
                            Text(
                                sub.name.uppercase(),
                                color = g.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(sub.themes) { t ->
                            Text(
                                t.name,
                                color = g.text,
                                fontSize = 15.sp,
                                modifier = Modifier.fillMaxWidth().clickable { onChosen(t.id) }.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { if (cat != null) category = null else onDismiss() }) {
                Text(if (cat != null) "Retour" else "Annuler")
            }
        },
    )
}
