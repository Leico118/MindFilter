package com.mindfilter.app.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Apprentissage par l'exemple : des empreintes d'images associées à un thème (« à masquer »)
 * ou marquées « sans problème » (pour éviter les fausses alertes).
 *
 *  - Exemples INTÉGRÉS : fournis avec l'appli (fichier assets/vision/learned.json), issus des
 *    fichiers d'apprentissage exportés puis intégrés à une mise à jour.
 *  - Exemples LOCAUX : ajoutés sur ce téléphone depuis le mode développeur ; ils servent tout
 *    de suite à la détection et peuvent être exportés.
 *
 * Seules des empreintes (listes de nombres) sont gardées : jamais les images elles-mêmes.
 * Une empreinte ne permet pas de reconstituer l'image.
 */
object LearnedExamples {
    class Example(val theme: String, val safe: Boolean, val vector: FloatArray, val builtIn: Boolean)
    class Match(val theme: String, val similarity: Float, val bestSafe: Float)

    const val FORMAT = "mindfilter-apprentissage"
    private const val LOCAL_FILE = "learned_local.json"
    private const val PREFS = "mindfilter_learning"

    private val examples = ArrayList<Example>()
    @Volatile private var loaded = false

    /** Change à chaque modification : les analyses déjà faites doivent alors être refaites. */
    @Volatile var revision = 0
        private set

    /** Ressemblance minimale pour masquer (réglable dans le mode développeur). */
    @Volatile var threshold = 0.80f
        private set

    /** Écart minimal avec l'exemple « sans problème » le plus proche. */
    const val MARGIN = 0.02f

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val app = ctx.applicationContext
            threshold = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("threshold", 0.80f)
            try {
                app.assets.open("vision/learned.json").use { parse(it.bufferedReader().readText(), builtIn = true) }
                    .let { examples.addAll(it) }
            } catch (_: Exception) {
            }
            try {
                val f = File(app.filesDir, LOCAL_FILE)
                if (f.exists()) examples.addAll(parse(f.readText(), builtIn = false))
            } catch (_: Exception) {
            }
            loaded = true
            revision++
        }
    }

    fun setThreshold(ctx: Context, value: Float) {
        threshold = value.coerceIn(0.60f, 0.95f)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("threshold", threshold).apply()
        revision++
    }

    fun add(ctx: Context, theme: String, safe: Boolean, vector: FloatArray) {
        ensureLoaded(ctx)
        synchronized(this) {
            examples.add(Example(if (safe) "" else theme, safe, vector, builtIn = false))
            saveLocal(ctx)
            revision++
        }
    }

    /** Efface les exemples locaux d'un thème ("" = exemples « sans problème »), ou tous si null. */
    fun clearLocal(ctx: Context, theme: String? = null) {
        ensureLoaded(ctx)
        synchronized(this) {
            examples.removeAll { !it.builtIn && (theme == null || it.theme == theme) }
            saveLocal(ctx)
            revision++
        }
    }

    /** Nombre d'exemples par thème ("" = sans problème) : (locaux, intégrés). */
    fun counts(ctx: Context): Map<String, Pair<Int, Int>> {
        ensureLoaded(ctx)
        synchronized(this) {
            val m = LinkedHashMap<String, Pair<Int, Int>>()
            for (e in examples) {
                val (l, b) = m[e.theme] ?: (0 to 0)
                m[e.theme] = if (e.builtIn) l to b + 1 else l + 1 to b
            }
            return m
        }
    }

    /** L'image ressemble-t-elle assez à un exemple d'un thème activé ? */
    fun match(vector: FloatArray, enabledThemes: Set<String>): Match? {
        val snapshot = synchronized(this) { examples.toList() }
        return bestMatch(snapshot, vector, enabledThemes, threshold)
    }

    /** Meilleure ressemblance pour chaque thème (outil de test du mode développeur). */
    fun scores(vector: FloatArray): List<Pair<String, Float>> {
        val snapshot = synchronized(this) { examples.toList() }
        val best = HashMap<String, Float>()
        for (e in snapshot) {
            if (e.vector.size != vector.size) continue
            val s = ImageEmbedder.similarity(e.vector, vector)
            if (s > (best[e.theme] ?: -1f)) best[e.theme] = s
        }
        return best.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** Fichier à envoyer pour intégrer cet apprentissage à une mise à jour (exemples locaux). */
    fun exportJson(ctx: Context): String {
        ensureLoaded(ctx)
        val local = synchronized(this) { examples.filter { !it.builtIn } }
        return toJson(local, ctx)
    }

    // --- interne -----------------------------------------------------------------------------

    internal fun bestMatch(list: List<Example>, vector: FloatArray, enabled: Set<String>, threshold: Float): Match? {
        var bestTheme: String? = null
        var bestPos = -1f
        var bestSafe = -1f
        for (e in list) {
            if (e.vector.size != vector.size) continue
            val s = ImageEmbedder.similarity(e.vector, vector)
            if (e.safe) {
                if (s > bestSafe) bestSafe = s
            } else if (e.theme in enabled && s > bestPos) {
                bestPos = s
                bestTheme = e.theme
            }
        }
        val theme = bestTheme ?: return null
        if (bestPos < threshold || bestPos < bestSafe + MARGIN) return null
        return Match(theme, bestPos, bestSafe)
    }

    private fun saveLocal(ctx: Context) {
        try {
            File(ctx.applicationContext.filesDir, LOCAL_FILE).writeText(toJson(examples.filter { !it.builtIn }, ctx))
        } catch (_: Exception) {
        }
    }

    private fun toJson(list: List<Example>, ctx: Context): String {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("theme", e.theme)
                    .put("safe", e.safe)
                    .put("v", encode(e.vector)),
            )
        }
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("app", version)
            .put("dimension", list.firstOrNull()?.vector?.size ?: 0)
            .put("examples", arr)
            .toString()
    }

    /** Lit un fichier d'apprentissage (exporté ou intégré). */
    fun parse(json: String, builtIn: Boolean): List<Example> {
        val o = JSONObject(json)
        if (o.optString("format") != FORMAT) return emptyList()
        val arr = o.optJSONArray("examples") ?: return emptyList()
        val out = ArrayList<Example>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val v = decode(e.optString("v")) ?: continue
            out.add(Example(e.optString("theme"), e.optBoolean("safe"), v, builtIn))
        }
        return out
    }

    private fun encode(v: FloatArray): String {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) b.putFloat(x)
        return Base64.getEncoder().encodeToString(b.array())
    }

    private fun decode(s: String): FloatArray? = try {
        val b = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray(b.remaining() / 4) { b.getFloat() }
    } catch (_: Exception) {
        null
    }
}
