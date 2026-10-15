package com.mindfilter.app.engine

import com.mindfilter.app.data.CustomFilter
import com.mindfilter.app.data.EXCLUSIONS
import com.mindfilter.app.data.Sensitivity
import com.mindfilter.app.data.Theme
import java.text.Normalizer
import java.util.Locale

/** Résultat d'une détection : le thème reconnu et son score. */
data class MatchResult(val id: String, val label: String, val score: Int)

/**
 * Point d'extension : la v0.1 utilise [KeywordClassifier]. Un futur modèle local de
 * classification sémantique (TFLite / ONNX) pourra implémenter cette même interface
 * et être chaîné derrière le moteur rapide sans toucher au service.
 */
interface ContentClassifier {
    fun classify(text: String): MatchResult?
}

object TextNormalizer {
    /** Minuscules, sans accents, ponctuation remplacée par des espaces simples. */
    fun normalize(input: String, keepStar: Boolean = false): String {
        val lower = input.lowercase(Locale.ROOT).replace("œ", "oe").replace("æ", "ae")
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        var lastSpace = true
        for (c in nfd) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if ((c in 'a'..'z') || (c in '0'..'9') || (keepStar && c == '*')) {
                sb.append(c)
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        return sb.toString().trim()
    }
}

/** Une expression compilée. [key] sert de pré-filtre rapide (3 premières lettres). */
class CompiledAlt(val key: String?, val regex: Regex) {
    companion object {
        fun compile(raw: String): CompiledAlt? {
            if (raw.startsWith("re:")) {
                return try {
                    CompiledAlt(null, Regex(raw.substring(3)))
                } catch (e: Exception) {
                    null
                }
            }
            val words = TextNormalizer.normalize(raw, keepStar = true)
                .split(' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.trimEnd('*').isNotEmpty() }
            if (words.isEmpty()) return null
            val body = words.joinToString(" ") { w ->
                if (w.endsWith("*")) Regex.escape(w.trimEnd('*').replace("*", "")) + "[a-z0-9]*"
                else Regex.escape(w.replace("*", ""))
            }
            val key = words.first().trimEnd('*').replace("*", "").take(3)
            return CompiledAlt(key, Regex("(?<![a-z0-9])$body(?![a-z0-9])"))
        }
    }
}

private class CompiledRule(val weight: Int, val groups: List<List<CompiledAlt>>)

private class CompiledTheme(val id: String, val label: String, val threshold: Int, val rules: List<CompiledRule>)

/**
 * Moteur rapide v0.1 : mots-clés, préfixes, expressions et co-occurrences pondérées.
 * Entièrement local, aucune donnée ne sort du téléphone.
 */
class KeywordClassifier(
    themes: Collection<Theme>,
    customFilters: List<CustomFilter>,
    sensitivity: Sensitivity,
) : ContentClassifier {

    private val altCache = HashMap<String, CompiledAlt?>()
    private val compiled: List<CompiledTheme>
    private val exclusions: List<Regex> = EXCLUSIONS.mapNotNull { CompiledAlt.compile(it)?.regex }

    private val resultCache = object : LinkedHashMap<String, MatchResult>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MatchResult>?): Boolean = size > 800
    }

    val isEmpty: Boolean

    init {
        val list = ArrayList<CompiledTheme>()
        for (t in themes) {
            val rules = t.rules.mapNotNull { r ->
                val groups = r.groups.map { g -> g.mapNotNull { alt(it) } }
                if (groups.isEmpty() || groups.any { it.isEmpty() }) null else CompiledRule(r.weight, groups)
            }
            if (rules.isNotEmpty()) list.add(CompiledTheme(t.id, t.name, sensitivity.threshold, rules))
        }
        for (f in customFilters) {
            if (!f.enabled) continue
            val kws = f.keywords.map { it.trim() }.filter { it.isNotEmpty() }
            if (kws.isEmpty()) continue
            // Strict : chaque mot-clé devient un préfixe (« incendie » → incendies, incendié…).
            val alts = kws.mapNotNull { k ->
                val pattern = if (f.sensitivity == Sensitivity.STRICT && !k.endsWith("*")) "$k*" else k
                alt(pattern)
            }
            if (alts.isEmpty()) continue
            // Chaque mot-clé distinct vaut 2 points ; seuil 2 (un mot-clé) ou 4 (léger : deux mots-clés).
            val rules = alts.map { CompiledRule(2, listOf(listOf(it))) }
            val threshold = if (f.sensitivity == Sensitivity.LIGHT && alts.size > 1) 4 else 2
            list.add(CompiledTheme("custom:${f.id}", f.name, threshold, rules))
        }
        compiled = list
        isEmpty = list.isEmpty()
    }

    private fun alt(raw: String): CompiledAlt? = altCache.getOrPut(raw) { CompiledAlt.compile(raw) }

    override fun classify(text: String): MatchResult? {
        if (isEmpty || text.length < 8) return null
        resultCache[text]?.let { return if (it === NO_MATCH) null else it }
        val r = doClassify(text)
        resultCache[text] = r ?: NO_MATCH
        return r
    }

    private fun doClassify(text: String): MatchResult? {
        var norm = TextNormalizer.normalize(if (text.length > 1500) text.substring(0, 1500) else text)
        for (ex in exclusions) if (ex.containsMatchIn(norm)) norm = ex.replace(norm, " ")
        if (norm.length < 6) return null

        val keys = HashSet<String>()
        for (tok in norm.split(' ')) {
            if (tok.isEmpty()) continue
            keys.add(tok.take(1)); keys.add(tok.take(2)); keys.add(tok.take(3))
        }
        val memo = HashMap<CompiledAlt, Boolean>()
        fun hit(a: CompiledAlt): Boolean = memo.getOrPut(a) {
            (a.key == null || a.key in keys) && a.regex.containsMatchIn(norm)
        }

        var best: MatchResult? = null
        for (t in compiled) {
            var score = 0
            for (rule in t.rules) {
                if (rule.groups.all { g -> g.any { hit(it) } }) score += rule.weight
            }
            if (score >= t.threshold && (best == null || score > best.score)) {
                best = MatchResult(t.id, t.label, score)
            }
        }
        return best
    }

    companion object {
        private val NO_MATCH = MatchResult("", "", 0)
    }
}
