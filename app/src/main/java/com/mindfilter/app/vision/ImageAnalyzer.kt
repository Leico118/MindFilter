package com.mindfilter.app.vision

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.mindfilter.app.engine.ContentClassifier
import java.util.concurrent.Executor

/**
 * Analyse des images affichées (option « Analyse des images », Android 11 et plus).
 *
 * Fonctionnement : quand de nouvelles images apparaissent dans une appli surveillée, MindFilter
 * prend une capture de l'écran (au plus ~2 fois par seconde), découpe chaque image, puis :
 *  1. reconnaît les objets (araignée, serpent, arme, char…) ;
 *  2. lit le texte écrit dans l'image et le passe au même moteur que le texte normal.
 * La capture reste en mémoire le temps de l'analyse puis est effacée : rien n'est enregistré
 * ni envoyé. Chaque image n'est analysée qu'une fois (résultat mémorisé).
 */
@RequiresApi(Build.VERSION_CODES.R)
class ImageAnalyzer(
    private val service: AccessibilityService,
    /** Appelé quand une image vient d'être reconnue comme à masquer (pour relancer l'analyse de l'écran). */
    private val onNewHit: () -> Unit,
) {
    class Candidate(val key: String, val node: AccessibilityNodeInfo, val bounds: Rect, val zone: Boolean = false)

    private val thread = HandlerThread("mindfilter-vision").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    @Volatile var classifier: ContentClassifier? = null
    @Volatile var enabledThemes: Set<String> = emptySet()
    /** Zones déjà couvertes par un masque (l'image dessous n'est pas visible sur la capture). */
    @Volatile var maskedAreas: List<Rect> = emptyList()

    @Volatile private var pending: List<Candidate> = emptyList()
    @Volatile private var busy = false
    private var lastShot = 0L

    private var objects: ObjectRecognizer? = null
    private var reader: TextReader? = null
    private var embedder: ImageEmbedder? = null
    private var nsfw: NsfwDetector? = null
    @Volatile var restrictionLevel = 5
    /** Hauteur à laquelle chaque image a été jugée « sans problème » (pour la rejuger si elle
     *  n'était qu'en partie visible). */
    private val cleanHeights = HashMap<String, Int>()
    private var learnedRevision = -1

    /** Résultat par image : libellé du thème, ou "" si l'image est sans problème. */
    private val verdicts = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 600
    }
    /** Même image déjà vue ailleurs (empreinte visuelle) : pas besoin de la réanalyser. */
    private val byHash = object : LinkedHashMap<Long, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 600
    }

    fun labelFor(key: String): String? = synchronized(verdicts) { verdicts[key] }?.takeIf { it.isNotEmpty() }

    private fun known(key: String) = synchronized(verdicts) { verdicts.containsKey(key) }

    /** À (re)analyser : jamais vue, ou jugée sans problème alors qu'elle était peu visible. */
    private fun needsAnalysis(c: Candidate): Boolean = synchronized(verdicts) {
        val v = verdicts[c.key] ?: return@synchronized true
        if (v.isNotEmpty()) return@synchronized false
        val h = cleanHeights[c.key] ?: return@synchronized false
        c.bounds.height() > h * 13 / 10
    }

    /** Nouvelles images visibles : on en analyse quelques-unes à la prochaine capture. */
    fun request(candidates: List<Candidate>) {
        VisionStats.candidates = candidates.size
        val todo = candidates.filter { needsAnalysis(it) }
        if (todo.isEmpty()) return
        pending = todo
        if (!busy) {
            busy = true
            val wait = (MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastShot)).coerceAtLeast(0L)
            handler.postDelayed({ capture() }, wait)
        }
    }

    private fun capture() {
        lastShot = SystemClock.uptimeMillis()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) = process(result)
                    override fun onFailure(errorCode: Int) {
                        VisionStats.shotsFailed++
                        VisionStats.lastError = VisionStats.errorText(errorCode)
                        busy = false
                        // Captures trop rapprochées : on réessaie un peu plus tard.
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            val rest = pending.filter { !known(it.key) }
                            if (rest.isNotEmpty()) handler.postDelayed({ request(rest) }, MIN_INTERVAL_MS)
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            busy = false
        }
    }

    private fun process(result: AccessibilityService.ScreenshotResult) {
        val hb = result.hardwareBuffer
        var screen: Bitmap? = null
        try {
            val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
            screen = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
            wrapped?.recycle()
        } catch (e: Throwable) {
        } finally {
            hb.close()
        }
        val shot = screen
        if (shot == null) {
            VisionStats.shotsFailed++
            VisionStats.lastError = "capture illisible"
            busy = false
            return
        }
        VisionStats.shotsOk++
        var hit = false
        var list0: List<Candidate> = emptyList()
        try {
            if (objects == null) objects = ObjectRecognizer(service)
            if (reader == null) reader = TextReader(service)
            if (embedder == null) embedder = ImageEmbedder(service)
            if (nsfw == null) nsfw = NsfwDetector(service)
            VisionStats.models = listOf(
                "adultes" to (nsfw?.available == true),
                "empreintes" to (embedder?.available == true),
                "objets" to (objects?.available == true),
                "texte" to (reader?.available == true),
            ).joinToString(" · ") { (n, ok) -> "$n ${if (ok) "✓" else "✗"}" }
            LearnedExamples.ensureLoaded(service)
            // Apprentissage modifié (mode développeur) : on rejuge les images.
            if (LearnedExamples.revision != learnedRevision) {
                learnedRevision = LearnedExamples.revision
                reset()
            }
            val masks = maskedAreas
            // Les plus grandes images d'abord, 6 au plus par capture (grilles de photos).
            val list = pending.filter { needsAnalysis(it) }
                .sortedByDescending { it.bounds.width() * it.bounds.height() }
                .take(6)
            list0 = list
            for (c in list) {
                val r = Rect()
                try {
                    if (!c.node.refresh()) continue
                    c.node.getBoundsInScreen(r)
                } catch (e: Exception) {
                    continue
                }
                if (!r.intersect(0, 0, shot.width, shot.height)) continue
                if (r.width() < 100 || r.height() < 100) continue
                if (masks.any { it.contains(r.centerX(), r.centerY()) }) continue
                var crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                if (c.zone) {
                    // Publication entière : on y cherche la bande qui contient l'image.
                    val band = ImageFinder.findImageBand(crop)
                    if (band == null) {
                        crop.recycle()
                        synchronized(verdicts) { verdicts[c.key] = "" }
                        continue
                    }
                    val sub = Bitmap.createBitmap(crop, band.left, band.top, band.width(), band.height())
                    crop.recycle()
                    crop = sub
                }
                val hash = dHash(crop)
                val cached = synchronized(byHash) { byHash[hash] }
                val label = cached ?: analyze(crop).also { synchronized(byHash) { byHash[hash] = it } }
                crop.recycle()
                synchronized(verdicts) {
                    verdicts[c.key] = label
                    if (label.isEmpty()) cleanHeights[c.key] = r.height() else cleanHeights.remove(c.key)
                }
                VisionStats.analyzed++
                if (label.isNotEmpty()) {
                    hit = true
                    VisionStats.hits++
                    VisionStats.lastLabel = label
                }
            }
        } catch (e: Throwable) {
        } finally {
            shot.recycle()
            busy = false
        }
        if (hit) onNewHit()
        // D'autres images attendent : nouvelle capture dans un instant.
        val rest = pending.filter { needsAnalysis(it) && it !in list0 }
        if (rest.isNotEmpty()) request(rest)
    }

    private fun analyze(img: Bitmap): String {
        val themes = enabledThemes
        // 0. Détecteur de contenus pour adultes (si un thème « adultes » est coché)
        if (themes.any { it.startsWith("adulte.") }) {
            val sc = nsfw?.takeIf { it.available }?.score(img) ?: -1f
            if (sc >= 0f) {
                VisionStats.lastNsfw = "${(sc * 100).toInt()} %"
                if (sc >= NsfwDetector.thresholdFor(restrictionLevel)) return "Contenu pour adultes (image)"
            }
        }
        // 1. Ressemblance avec les exemples appris : image entière ET son centre (une image
        //    affichée dans une appli est souvent recadrée).
        embedder?.takeIf { it.available }?.let { emb ->
            val vectors = ArrayList<FloatArray>(2)
            emb.embed(img)?.let { vectors.add(it) }
            if (img.width >= 60 && img.height >= 60) {
                val center = Bitmap.createBitmap(img, img.width / 10, img.height / 10, img.width * 8 / 10, img.height * 8 / 10)
                emb.embed(center)?.let { vectors.add(it) }
                if (center !== img) center.recycle()
            }
            var best: LearnedExamples.Match? = null
            for (v in vectors) {
                LearnedExamples.scores(v).firstOrNull()?.let { (t, sim) ->
                    VisionStats.lastBest = "${if (t.isEmpty()) "Sans problème" else com.mindfilter.app.data.TaxonomyIndex.themeName(t)} ${(sim * 100).toInt()} %"
                }
                val m = LearnedExamples.match(v, themes)
                if (m != null && (best == null || m.similarity > best.similarity)) best = m
            }
            best?.let { m -> return "${com.mindfilter.app.data.TaxonomyIndex.themeName(m.theme)} (image)" }
        }
        // 1. Objets reconnus
        objects?.takeIf { it.available }?.recognize(img)?.forEach { (name, p) ->
            val rule = ImageLabels.ruleFor(name) ?: return@forEach
            if (p >= rule.minProbability && rule.themeId in themes) return rule.label
        }
        // 2. Texte écrit dans l'image
        val text = reader?.takeIf { it.available }?.read(img).orEmpty()
        if (text.length >= 8) {
            classifier?.classify(text)?.let { return "${it.label} (texte de l'image)" }
        }
        return ""
    }

    /** Empreinte visuelle (dHash 64 bits) : identique pour la même image, même redimensionnée. */
    private fun dHash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 9, 8, true)
        var h = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val l = luma(s.getPixel(x, y))
                val r = luma(s.getPixel(x + 1, y))
                if (l > r) h = h or (1L shl bit)
                bit++
            }
        }
        s.recycle()
        return h
    }

    private fun luma(p: Int) = ((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114

    fun reset() {
        synchronized(verdicts) {
            verdicts.clear()
            cleanHeights.clear()
        }
        synchronized(byHash) { byHash.clear() }
    }

    fun quit() {
        handler.post {
            objects?.close()
            reader?.close()
            embedder?.close()
            nsfw?.close()
        }
        thread.quitSafely()
    }

    companion object {
        /** Android limite les captures d'un service d'accessibilité (~3 par seconde). */
        const val MIN_INTERVAL_MS = 450L
    }
}
