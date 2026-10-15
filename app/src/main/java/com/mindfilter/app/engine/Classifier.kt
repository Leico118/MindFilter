package com.mindfilter.app.engine

import com.mindfilter.app.data.CustomFilter
import com.mindfilter.app.data.EXCLUSIONS
import com.mindfilter.app.data.FICTION_MARKERS
import com.mindfilter.app.data.HUMOR_MARKERS
import com.mindfilter.app.data.STRONG
import com.mindfilter.app.data.Sensitivity
import com.mindfilter.app.data.Theme
import java.text.Normalizer
import java.util.Locale

/** Résultat d'une détection : le thème reconnu et son score. */
data class MatchResult(val id: String, val label: String, val score: Int)

/**
 * Point d'extension : [KeywordClassifier] est le moteur rapide. Un futur modèle local de
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

private class CompiledRule(val weight: Int, val groups: List<List<CompiledAlt>>) {
    val isCombo: Boolean get() = groups.size > 1
}

private class CompiledTheme(
    val id: String,
    val label: String,
    val rules: List<CompiledRule>,
    /** Ignore le contexte humour / fiction (contenus pour adultes). */
    val ignoreContext: Boolean = false,
    /** Seuil propre (filtres personnalisés) ; null = seuil du niveau de sensibilité. */
    val fixedThreshold: Int? = null,
)

/**
 * Réglages internes de chaque niveau de sensibilité.
 *
 * - [combos] : les associations de mots (« retrouvé » + « sans vie ») sont-elles prises en compte ?
 * - [window] : les mots d'une association doivent être à moins de N mots les uns des autres.
 * - [minWeight] : poids minimal d'une règle pour compter (4 = seulement les mots-clés forts).
 * - [humorPenalty]/[fictionPenalty] : points exigés en plus si le texte contient des marques
 *   d'humour (😂, mdr…) ou parle de jeu vidéo / film / série. -1 = jamais masqué dans ce cas.
 */
private data class Level(
    val threshold: Int,
    val combos: Boolean,
    val window: Int,
    val minWeight: Int,
    val humorPenalty: Int,
    val fictionPenalty: Int,
)

/** Les 9 crans de la jauge « Niveau de filtrage » (1 = très léger … 9 = très strict). */
private fun levelOf(n: Int): Level = when (n.coerceIn(1, 9)) {
    1 -> Level(threshold = 4, combos = false, window = 0, minWeight = STRONG, humorPenalty = -1, fictionPenalty = -1)
    2 -> Level(threshold = 4, combos = true, window = 3, minWeight = STRONG, humorPenalty = -1, fictionPenalty = -1)
    3 -> Level(threshold = 4, combos = true, window = 6, minWeight = STRONG, humorPenalty = -1, fictionPenalty = 4)
    4 -> Level(threshold = 4, combos = true, window = 8, minWeight = 2, humorPenalty = 6, fictionPenalty = 4)
    5 -> Level(threshold = 4, combos = true, window = 10, minWeight = 2, humorPenalty = 4, fictionPenalty = 4)
    6 -> Level(threshold = 4, combos = true, window = 12, minWeight = 1, humorPenalty = 4, fictionPenalty = 2)
    7 -> Level(threshold = 3, combos = true, window = 16, minWeight = 1, humorPenalty = 2, fictionPenalty = 2)
    8 -> Level(threshold = 2, combos = true, window = 25, minWeight = 1, humorPenalty = 2, fictionPenalty = 0)
    else -> Level(threshold = 2, combos = true, window = 40, minWeight = 1, humorPenalty = 0, fictionPenalty = 0)
}

/**
 * Moteur rapide : mots-clés, préfixes, expressions et associations de mots proches,
 * pondérés, avec prise en compte du contexte (humour, jeux vidéo, films…).
 * Entièrement local, aucune donnée ne sort du téléphone.
 */
class KeywordClassifier(
    themes: Collection<Theme>,
    customFilters: List<CustomFilter>,
    restrictionLevel: Int,
    /** Mots « toujours afficher » saisis par l'utilisateur. */
    exceptions: List<String> = emptyList(),
) : ContentClassifier {

    private val level = levelOf(restrictionLevel)
    private val altCache = HashMap<String, CompiledAlt?>()
    private val compiled: List<CompiledTheme>
    private val exclusions: List<Regex> = EXCLUSIONS.mapNotNull { CompiledAlt.compile(it)?.regex }
    private val humorWords: List<Regex> = HUMOR_MARKERS.mapNotNull { CompiledAlt.compile(it)?.regex }
    private val fictionWords: List<Regex> = FICTION_MARKERS.mapNotNull { CompiledAlt.compile(it)?.regex }

    private val resultCache = object : LinkedHashMap<String, MatchResult>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MatchResult>?): Boolean = size > 800
    }

    val isEmpty: Boolean

    private class UserException(val raw: String, val regex: Regex, val coveredThemes: Set<String>)

    private val userExceptions: List<UserException>

    init {
        val list = ArrayList<CompiledTheme>()
        for (t in themes) {
            val rules = t.rules.mapNotNull { r ->
                if (r.weight < level.minWeight) return@mapNotNull null
                if (r.groups.size > 1 && !level.combos) return@mapNotNull null
                val groups = r.groups.map { g -> g.mapNotNull { alt(it) } }
                if (groups.isEmpty() || groups.any { it.isEmpty() }) null else CompiledRule(r.weight, groups)
            }
            if (rules.isNotEmpty()) list.add(CompiledTheme(t.id, t.name, rules, ignoreContext = t.ignoreContext))
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
            // Chaque mot-clé distinct vaut 2 points ; un mot-clé suffit (seuil 2),
            // sauf en léger / très léger où il en faut deux (seuil 4).
            val rules = alts.map { CompiledRule(2, listOf(listOf(it))) }
            val needTwo = (f.sensitivity == Sensitivity.LIGHT || f.sensitivity == Sensitivity.VERY_LIGHT) && alts.size > 1
            list.add(CompiledTheme("custom:${f.id}", f.name, rules, fixedThreshold = if (needTwo) 4 else 2))
        }
        compiled = list
        isEmpty = list.isEmpty()
        userExceptions = exceptions.mapNotNull { e ->
            val rx = compileException(e) ?: return@mapNotNull null
            // Thèmes « couverts » par l'exception : ceux dont le lexique reconnaît ce mot.
            // « tremblement de terre » couvre le thème Séismes, donc un texte qui contient
            // l'exception n'est plus masqué au titre des séismes, même s'il dit aussi « séisme ».
            UserException(e, rx, themesMatching(TextNormalizer.normalize(e)))
        }
    }

    /** Thèmes (cochés) dont au moins une règle reconnaît ce texte, sans condition de distance. */
    private fun themesMatching(norm: String): Set<String> {
        if (norm.isBlank()) return emptySet()
        val out = HashSet<String>()
        for (t in compiled) {
            if (t.fixedThreshold != null) continue
            val hit = t.rules.any { r -> r.groups.all { g -> g.any { it.regex.containsMatchIn(norm) } } }
            if (hit) out.add(t.id)
        }
        return out
    }

    /** Mot ou expression d'exception, tolérant au pluriel (« tremblements » = « tremblement »). */
    private fun compileException(raw: String): Regex? {
        val words = TextNormalizer.normalize(raw, keepStar = true).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val body = words.joinToString(" ") { w ->
            when {
                w.endsWith("*") -> Regex.escape(w.trimEnd('*')) + "[a-z0-9]*"
                w.length > 3 && (w.endsWith("s") || w.endsWith("x")) -> Regex.escape(w.dropLast(1)) + "[sx]?"
                else -> Regex.escape(w) + "[sx]?"
            }
        }
        return Regex("(?<![a-z0-9])$body(?![a-z0-9])")
    }

    /** Pour l'écran Filtres : pour chaque exception, les noms des thèmes qu'elle annule. */
    fun exceptionCoverage(): Map<String, List<String>> =
        userExceptions.associate { ex -> ex.raw to compiled.filter { it.id in ex.coveredThemes }.map { it.label } }

    /** L'exception qui a empêché le masquage de ce texte, s'il y en a une. */
    fun exceptionHit(text: String): String? {
        val norm = TextNormalizer.normalize(text)
        return userExceptions.firstOrNull { it.regex.containsMatchIn(norm) }?.raw
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
        val raw = if (text.length > 1500) text.substring(0, 1500) else text
        // Les fins de phrase et séparateurs (« . ! ? · | », retour à la ligne) deviennent un
        // mot-repère : une association de mots ne peut pas enjamber deux phrases.
        var norm = TextNormalizer.normalize(SENTENCE_END.replace(raw, " $SEP "))
        for (ex in exclusions) if (ex.containsMatchIn(norm)) norm = ex.replace(norm, " ")
        // Exceptions de l'utilisateur : on retire le mot du texte et on écarte les thèmes qu'il couvre.
        val skipThemes = HashSet<String>()
        for (ex in userExceptions) {
            if (ex.regex.containsMatchIn(norm)) {
                norm = ex.regex.replace(norm, " ")
                skipThemes.addAll(ex.coveredThemes)
            }
        }
        norm = norm.replace(MULTI_SPACE, " ").trim()
        if (norm.length < 6) return null

        // Contexte : humour (émojis rieurs, mdr…) ou fiction (jeu vidéo, film, série…).
        val humor = LAUGH_EMOJI.containsMatchIn(raw) || humorWords.any { it.containsMatchIn(norm) }
        val fiction = fictionWords.any { it.containsMatchIn(norm) }
        // blocked = aux niveaux bas, ce contexte empêche tout masquage (sauf filtres
        // personnels et thèmes qui ignorent le contexte, comme les contenus pour adultes).
        var penalty = 0
        var blocked = false
        if (humor) {
            if (level.humorPenalty < 0) blocked = true else penalty += level.humorPenalty
        }
        if (fiction) {
            if (level.fictionPenalty < 0) blocked = true else penalty += level.fictionPenalty
        }

        // Index « position du caractère → numéro du mot » pour mesurer les distances.
        // Changer de phrase compte comme un grand saut de distance.
        val wordAt = IntArray(norm.length)
        var w = 0
        var i = 0
        while (i < norm.length) {
            if (norm[i] == ' ') {
                w++
                if (norm.startsWith(SEP, i + 1) && (i + 1 + SEP.length == norm.length || norm[i + 1 + SEP.length] == ' ')) w += 1000
            }
            wordAt[i] = w
            i++
        }
        val keys = HashSet<String>()
        for (tok in norm.split(' ')) {
            if (tok.isEmpty()) continue
            keys.add(tok.take(1)); keys.add(tok.take(2)); keys.add(tok.take(3))
        }
        val memo = HashMap<CompiledAlt, IntArray>()
        fun positions(a: CompiledAlt): IntArray = memo.getOrPut(a) {
            if (a.key != null && a.key !in keys) EMPTY
            else a.regex.findAll(norm).map { wordAt[it.range.first] }.take(30).toList().toIntArray()
        }

        var best: MatchResult? = null
        for (t in compiled) {
            val custom = t.fixedThreshold != null
            if (blocked && !custom && !t.ignoreContext) continue
            if (!custom && t.id in skipThemes) continue
            var score = 0
            for (rule in t.rules) {
                if (matches(rule, ::positions)) score += rule.weight
            }
            val threshold = (t.fixedThreshold ?: level.threshold) + if (custom || t.ignoreContext) 0 else penalty
            if (score >= threshold && (best == null || score > best.score)) {
                best = MatchResult(t.id, t.label, score)
            }
        }
        return best
    }

    /**
     * Une règle simple se déclenche si l'un de ses mots est présent. Une association
     * exige que les mots de chaque groupe soient proches (moins de [Level.window] mots) :
     * « retrouvé … sans vie » dans la même phrase, pas à deux bouts d'une page.
     */
    private fun matches(rule: CompiledRule, positions: (CompiledAlt) -> IntArray): Boolean {
        val perGroup = rule.groups.map { g ->
            val all = ArrayList<Int>()
            for (a in g) positions(a).forEach { all.add(it) }
            all
        }
        if (perGroup.any { it.isEmpty() }) return false
        if (!rule.isCombo) return true
        val anchor = perGroup[0]
        for (p in anchor) {
            val ok = perGroup.drop(1).all { grp -> grp.any { q -> kotlin.math.abs(q - p) <= level.window } }
            if (ok) return true
        }
        return false
    }

    companion object {
        private val NO_MATCH = MatchResult("", "", 0)
        private val EMPTY = IntArray(0)
        private val MULTI_SPACE = Regex(" {2,}")
        private const val SEP = "qzxsep"
        private val SENTENCE_END = Regex("[.!?\u2026\u00B7\u2022|\n\r]+")
        private val LAUGH_EMOJI = Regex("\uD83D\uDE02|\uD83E\uDD23|\uD83D\uDE06|\uD83D\uDE39|\uD83D\uDE05|\uD83D\uDC80")
    }
}
