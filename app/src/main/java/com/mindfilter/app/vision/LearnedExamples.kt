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
    @Volatile var threshold = 0.75f
        private set

    /** Écart minimal avec l'exemple « sans problème » le plus proche. */
    const val MARGIN = 0.02f

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val app = ctx.applicationContext
            threshold = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("threshold", 0.75f)
            try {
                app.assets.open("vision/learned.json").use { parse(it.bufferedReader().readText(), builtIn = true) }
                    .let { examples.addAll(it) }
            } catch (_: Exception) {
            }
            try {
                val f = File(app.filesDir, LOCAL_FILE)
                if (f.exists()) {
                    // Les exemples déjà intégrés à l'appli (fichier envoyé puis ajouté à une mise à
                    // jour) ne sont pas comptés deux fois.
                    val builtIns = examples.toList()
                    val local = parse(f.readText(), builtIn = false).filter { l ->
                        builtIns.none { b -> b.theme == l.theme && b.safe == l.safe && ImageEmbedder.similarity(b.vector, l.vector) > 0.9999f }
                    }
                    examples.addAll(local)
                }
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

    /** Ajout groupé (import d'un dossier) : un seul enregistrement pour tout le lot. */
    fun addAll(ctx: Context, theme: String, safe: Boolean, vectors: List<FloatArray>) {
        if (vectors.isEmpty()) return
        ensureLoaded(ctx)
        synchronized(this) {
            for (v in vectors) examples.add(Example(if (safe) "" else theme, safe, v, builtIn = false))
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
                    .put("h", encodeHalf(e.vector)),
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
            val v = (if (e.has("h")) decodeHalf(e.optString("h")) else decode(e.optString("v"))) ?: continue
            out.add(Example(e.optString("theme"), e.optBoolean("safe"), v, builtIn))
        }
        return out
    }

    // Stockage compact : nombres « demi-précision » (2 octets au lieu de 4), largement assez
    // précis pour comparer des empreintes. Les anciens fichiers (4 octets, champ "v") restent lisibles.
    private fun encodeHalf(v: FloatArray): String {
        val b = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) b.putShort(toHalf(x))
        return Base64.getEncoder().encodeToString(b.array())
    }

    private fun decodeHalf(s: String): FloatArray? = try {
        val b = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray(b.remaining() / 2) { fromHalf(b.getShort()) }
    } catch (_: Exception) {
        null
    }

    internal fun toHalf(f: Float): Short {
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val abs = bits and 0x7FFFFFFF
        val v = abs + 0x1000 // arrondi
        if (v >= 0x47800000) {
            // Trop grand (ou infini) : plus grande valeur possible.
            return (sign or 0x7BFF).toShort()
        }
        if (v >= 0x38800000) return (sign or ((v - 0x38000000) ushr 13)).toShort()
        if (v < 0x33000000) return sign.toShort()
        val e = abs ushr 23
        return (sign or ((((abs and 0x7FFFFF) or 0x800000) + (0x800000 ushr (e - 102))) ushr (126 - e))).toShort()
    }

    internal fun fromHalf(h: Short): Float {
        val x = h.toInt() and 0xFFFF
        val sign = if (x and 0x8000 != 0) -1f else 1f
        val exp = (x ushr 10) and 0x1F
        val mant = x and 0x3FF
        return when (exp) {
            0 -> sign * Math.scalb(mant.toFloat(), -24)
            31 -> sign * Float.MAX_VALUE
            else -> sign * Math.scalb(1f + mant / 1024f, exp - 15)
        }
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
