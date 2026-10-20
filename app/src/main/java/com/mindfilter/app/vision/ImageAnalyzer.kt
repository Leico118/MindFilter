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
 * Pour être le plus réactif possible :
 *  - Android 14 et plus : on capture UNIQUEMENT la fenêtre de l'appli (Facebook…), sans les
 *    masques de MindFilter par-dessus. Les nouvelles images peuvent donc être cachées
 *    préventivement pendant leur analyse (option), sans gêner l'analyse.
 *  - Analyse rapide d'abord (contenus pour adultes, exemples appris, objets) : chaque image
 *    reconnue est masquée AUSSITÔT, sans attendre les autres.
 *  - La lecture du texte dans l'image (lente) ne vient qu'ensuite.
 *
 * La capture reste en mémoire le temps de l'analyse puis est effacée : rien n'est enregistré
 * ni envoyé. Chaque image n'est analysée qu'une fois (résultat mémorisé).
 */
@RequiresApi(Build.VERSION_CODES.R)
class ImageAnalyzer(
    private val service: AccessibilityService,
    /** Appelé dès qu'un résultat change ce qui doit être masqué (pour relancer l'analyse de l'écran). */
    private val onNewHit: () -> Unit,
) {
    class Candidate(val key: String, val node: AccessibilityNodeInfo, val bounds: Rect, val zone: Boolean = false)

    private val thread = HandlerThread("mindfilter-vision").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    @Volatile var classifier: ContentClassifier? = null
    @Volatile var enabledThemes: Set<String> = emptySet()
    /** Zones déjà couvertes par un masque (utile seulement sans capture de fenêtre). */
    @Volatile var maskedAreas: List<Rect> = emptyList()
    @Volatile var restrictionLevel = 5

    @Volatile private var pending: List<Candidate> = emptyList()
    @Volatile private var busy = false
    private var lastShot = 0L

    /** Capture de la fenêtre seule disponible et fonctionnelle (Android 14+). */
    @Volatile private var windowCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private var objects: ObjectRecognizer? = null
    private var reader: TextReader? = null
    private var embedder: ImageEmbedder? = null
    private var nsfw: NsfwDetector? = null
    /** Hauteur à laquelle chaque image a été jugée « sans problème » (pour la rejuger si elle
     *  n'était qu'en partie visible). */
    private val cleanHeights = HashMap<String, Int>()
    private var learnedRevision = -1
    /** Première apparition de chaque image (le masquage préventif ne dure jamais plus de 3 s). */
    private val firstSeen = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 600
    }

    /** Résultat par image : libellé du thème, ou "" si l'image est sans problème. */
    private val verdicts = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 600
    }
    /** Même image déjà vue ailleurs (empreinte visuelle) : pas besoin de la réanalyser. */
    private val byHash = object : LinkedHashMap<Long, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 600
    }

    /** null = pas encore analysée, "" = sans problème, sinon libellé du thème reconnu. */
    fun statusFor(key: String): String? = synchronized(verdicts) { verdicts[key] }

    fun labelFor(key: String): String? = statusFor(key)?.takeIf { it.isNotEmpty() }

    /**
     * Faut-il cacher cette image en attendant son analyse ? Seulement si la capture de fenêtre
     * fonctionne (sinon le masque gênerait l'analyse), et jamais plus de 3 secondes.
     */
    fun precautionFor(key: String): Boolean {
        if (!windowCapture || known(key)) return false
        val now = SystemClock.uptimeMillis()
        val first = synchronized(firstSeen) { firstSeen.getOrPut(key) { now } }
        return now - first < 3000
    }

    private fun known(key: String) = synchronized(verdicts) { verdicts.containsKey(key) }

    /** À (re)analyser : jamais vue, ou jugée sans problème alors qu'elle était peu visible. */
    private fun needsAnalysis(c: Candidate): Boolean = synchronized(verdicts) {
        val v = verdicts[c.key] ?: return@synchronized true
        if (v.isNotEmpty()) return@synchronized false
        val h = cleanHeights[c.key] ?: return@synchronized false
        c.bounds.height() > h * 13 / 10
    }

    /** Nouvelles images visibles : capture dès que possible. */
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
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) = process(result, windowOrigin)
            override fun onFailure(errorCode: Int) {
                VisionStats.shotsFailed++
                VisionStats.lastError = VisionStats.errorText(errorCode)
                busy = false
                if (usingWindow && errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    // La capture de fenêtre ne marche pas sur ce téléphone : on revient à l'écran entier.
                    windowCapture = false
                }
                val rest = pending.filter { !known(it.key) }
                if (rest.isNotEmpty()) handler.postDelayed({ request(rest) }, MIN_INTERVAL_MS)
            }
        }
        try {
            val first = pending.firstOrNull()
            usingWindow = false
            windowOrigin = Rect()
            if (windowCapture && first != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val windowId = first.node.windowId
                // Origine de la fenêtre sur l'écran (normalement 0,0 pour une appli plein écran).
                service.rootInActiveWindow?.takeIf { it.windowId == windowId }?.getBoundsInScreen(windowOrigin)
                usingWindow = true
                service.takeScreenshotOfWindow(windowId, executor, callback)
            } else {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
            }
        } catch (e: Throwable) {
            if (usingWindow) windowCapture = false
            busy = false
        }
    }

    @Volatile private var usingWindow = false
    @Volatile private var windowOrigin = Rect()

    private fun process(result: AccessibilityService.ScreenshotResult, origin: Rect) {
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
        val windowOnly = usingWindow
        val crops = ArrayList<Pair<Candidate, Bitmap>>()
        var list0: List<Candidate> = emptyList()
        try {
            ensureModels()
            val masks = if (windowOnly) emptyList() else maskedAreas
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
                // Coordonnées écran → coordonnées dans la capture (fenêtre seule ou écran entier).
                r.offset(-origin.left, -origin.top)
                if (!r.intersect(0, 0, shot.width, shot.height)) continue
                if (r.width() < 100 || r.height() < 100) continue
                if (masks.any { it.contains(r.centerX() + origin.left, r.centerY() + origin.top) }) continue
                var crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                if (c.zone) {
                    // Publication entière : on y cherche la bande qui contient l'image.
                    val band = ImageFinder.findImageBand(crop)
                    if (band == null) {
                        crop.recycle()
                        setVerdict(c, "", r.height())
                        continue
                    }
                    val sub = Bitmap.createBitmap(crop, band.left, band.top, band.width(), band.height())
                    crop.recycle()
                    crop = sub
                }
                crops.add(c to crop)
            }
            shot.recycle()

            // 1re passe, rapide : chaque image reconnue est masquée tout de suite.
            val undecided = ArrayList<Triple<Candidate, Bitmap, Long>>()
            for ((c, crop) in crops) {
                val hash = dHash(crop)
                val cached = synchronized(byHash) { byHash[hash] }
                val label = cached ?: fastAnalyze(crop)
                if (label.isNotEmpty() || cached != null) {
                    if (cached == null) synchronized(byHash) { byHash[hash] = label }
                    setVerdict(c, label, crop.height)
                    crop.recycle()
                    if (label.isNotEmpty()) onNewHit()
                } else {
                    undecided.add(Triple(c, crop, hash))
                }
            }
            // 2e passe : lecture du texte dans les images restantes (plus lente).
            for ((c, crop, hash) in undecided) {
                val label = textAnalyze(crop)
                synchronized(byHash) { byHash[hash] = label }
                setVerdict(c, label, crop.height)
                crop.recycle()
            }
            // Les images sans problème cachées préventivement peuvent réapparaître.
            if (undecided.isNotEmpty()) onNewHit()
        } catch (e: Throwable) {
            for ((_, b) in crops) if (!b.isRecycled) b.recycle()
            if (!shot.isRecycled) shot.recycle()
        } finally {
            busy = false
        }
        // D'autres images attendent : nouvelle capture dans un instant.
        val rest = pending.filter { needsAnalysis(it) && it !in list0 }
        if (rest.isNotEmpty()) request(rest)
    }

    private fun setVerdict(c: Candidate, label: String, height: Int) {
        synchronized(verdicts) {
            verdicts[c.key] = label
            if (label.isEmpty()) cleanHeights[c.key] = height else cleanHeights.remove(c.key)
        }
        VisionStats.analyzed++
        if (label.isNotEmpty()) {
            VisionStats.hits++
            VisionStats.lastLabel = label
        }
    }

    private fun ensureModels() {
        if (objects == null) objects = ObjectRecognizer(service)
        if (reader == null) reader = TextReader(service)
        if (embedder == null) embedder = ImageEmbedder(service)
        if (nsfw == null) nsfw = NsfwDetector(service)
        VisionStats.models = listOf(
            "adultes" to (nsfw?.available == true),
            "empreintes" to (embedder?.available == true),
            "objets" to (objects?.available == true),
            "texte" to (reader?.available == true),
        ).joinToString(" · ") { (n, ok) -> "$n ${if (ok) "✓" else "✗"}" } +
            if (windowCapture) " · capture fenêtre ✓" else ""
        LearnedExamples.ensureLoaded(service)
        // Apprentissage modifié (mode développeur) : on rejuge les images.
        if (LearnedExamples.revision != learnedRevision) {
            learnedRevision = LearnedExamples.revision
            reset()
        }
    }

    /** Vérifications rapides : contenus pour adultes, exemples appris, objets. */
    private fun fastAnalyze(img: Bitmap): String {
        val themes = enabledThemes
        // Détecteur de contenus pour adultes (si un thème « adultes » est coché)
        if (themes.any { it.startsWith("adulte.") }) {
            val sc = nsfw?.takeIf { it.available }?.score(img) ?: -1f
            if (sc >= 0f) {
                VisionStats.lastNsfw = "${(sc * 100).toInt()} %"
                if (sc >= NsfwDetector.thresholdFor(restrictionLevel)) return "Contenu pour adultes (image)"
            }
        }
        // Ressemblance avec les exemples appris : image entière ET son centre (une image
        // affichée dans une appli est souvent recadrée).
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
        // Objets reconnus
        objects?.takeIf { it.available }?.recognize(img)?.forEach { (name, p) ->
            val rule = ImageLabels.ruleFor(name) ?: return@forEach
            if (p >= rule.minProbability && rule.themeId in themes) return rule.label
        }
        return ""
    }

    /** Texte écrit dans l'image (mèmes, captures d'articles…). */
    private fun textAnalyze(img: Bitmap): String {
        // Image réduite : la lecture est bien plus rapide et reste fiable pour un texte lisible.
        val src = if (img.width > 900) Bitmap.createScaledBitmap(img, 900, img.height * 900 / img.width, true) else img
        val text = reader?.takeIf { it.available }?.read(src).orEmpty()
        if (src !== img) src.recycle()
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
        const val MIN_INTERVAL_MS = 340L
    }
}
