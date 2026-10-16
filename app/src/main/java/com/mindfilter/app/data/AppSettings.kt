package com.mindfilter.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Sensitivity(val label: String, val description: String) {
    VERY_LIGHT("Très léger", "Seulement les mots sans ambiguïté (« attentat », « infarctus »…). Les blagues et les publications sur les jeux, films ou séries ne sont jamais masquées."),
    LIGHT("Léger", "Mots sans ambiguïté et associations très proches (« retrouvé sans vie »). Les blagues ne sont jamais masquées."),
    MODERATE("Modéré", "Ajoute les associations dans une même phrase. Les blagues et la fiction demandent beaucoup plus d'indices."),
    NORMAL("Normal", "Bon équilibre entre protection et faux positifs."),
    STRICT("Strict", "Masque aussi les contenus simplement évocateurs."),
}

/**
 * Niveau de filtrage global, de 1 (très léger) à 10 (maximal), réglé par la jauge.
 * Les filtres personnalisés gardent leur propre [Sensitivity].
 */
object RestrictionLevels {
    const val MIN = 1
    const val MAX = 9
    const val DEFAULT = 5

    /** Crans de la jauge : 1 ; 1,5 ; 2 ; … ; 5. */
    val names = listOf(
        "Très léger", "Très léger +", "Léger", "Léger +", "Moyen",
        "Moyen +", "Strict", "Strict +", "Très strict",
    )
    val descriptions = listOf(
        "Seulement les mots sans ambiguïté (« attentat », « infarctus »). Blagues, jeux, films et séries jamais masqués.",
        "Mots sans ambiguïté et associations collées (« retrouvé sans vie »). Blagues et fiction jamais masquées.",
        "Associations très proches. Blagues jamais masquées, fiction rarement.",
        "Associations proches et mots moins explicites. Blagues et fiction demandent beaucoup d'indices.",
        "Associations dans une même phrase. Blagues et fiction demandent beaucoup d'indices.",
        "Plus de mots pris en compte, phrases un peu plus longues.",
        "Large : bon niveau de protection, quelques faux positifs possibles.",
        "Masque aussi les contenus simplement évocateurs.",
        "Le plus large possible : le moindre indice suffit, même dans une blague.",
    )

    fun name(level: Int) = names[level.coerceIn(MIN, MAX) - 1]
    fun description(level: Int) = descriptions[level.coerceIn(MIN, MAX) - 1]

    /** Valeur affichée sur la jauge : 1, 1,5, 2 … 5. */
    fun display(level: Int): String {
        val l = level.coerceIn(MIN, MAX)
        val whole = (l + 1) / 2
        return if (l % 2 == 1) "$whole" else "$whole,5"
    }

    /** Ancien réglage à 5 niveaux (v0.1.2). */
    fun fromSensitivity(s: Sensitivity): Int = when (s) {
        Sensitivity.VERY_LIGHT -> 1
        Sensitivity.LIGHT -> 3
        Sensitivity.MODERATE -> 5
        Sensitivity.NORMAL -> 7
        Sensitivity.STRICT -> 8
    }

    /** Ancienne jauge à 10 crans (v0.1.4). */
    fun fromTenSteps(old: Int): Int = when (old.coerceIn(1, 10)) {
        8 -> 7
        9 -> 8
        10 -> 9
        else -> old
    }
}

/** Couleur d'accent de l'interface (version claire et sombre, couleur du texte posé dessus). */
enum class AccentColor(val label: String, val light: Long, val dark: Long, val onLight: Long, val onDark: Long) {
    BLUE("Bleu", 0xFF0A84FF, 0xFF3B9BFF, 0xFFFFFFFF, 0xFFFFFFFF),
    TEAL("Turquoise", 0xFF0097A7, 0xFF40C8E0, 0xFFFFFFFF, 0xFF00252B),
    GREEN("Vert", 0xFF2E9E4F, 0xFF32D74B, 0xFFFFFFFF, 0xFF002B0A),
    YELLOW("Miel", 0xFFC98A12, 0xFFF0B13A, 0xFFFFFFFF, 0xFF2B1D00),
    ORANGE("Orange", 0xFFF57C00, 0xFFFF9F0A, 0xFFFFFFFF, 0xFF2B1600),
    RED("Rouge", 0xFFE5342B, 0xFFFF6961, 0xFFFFFFFF, 0xFFFFFFFF),
    PINK("Rose", 0xFFE6337A, 0xFFFF6FA3, 0xFFFFFFFF, 0xFFFFFFFF),
    PURPLE("Violet", 0xFF9B45D6, 0xFFBF5AF2, 0xFFFFFFFF, 0xFFFFFFFF),
    INDIGO("Indigo", 0xFF5856D6, 0xFF7D7AFF, 0xFFFFFFFF, 0xFFFFFFFF),
    GRAPHITE("Noir", 0xFF1C1C1E, 0xFFE5E5EA, 0xFFFFFFFF, 0xFF111113),
}

enum class ThemeMode(val label: String) { SYSTEM("Automatique"), LIGHT("Clair"), DARK("Sombre") }

/** Thèmes de l'interface (inspirés de Radar). Le mode clair / sombre s'applique à Verre et Sobre. */
enum class AppStyle(val label: String, val description: String, val previewTop: Long, val previewBottom: Long) {
    VERRE("Verre", "Bulles translucides sur fond teinté", 0xFF4A3A1C, 0xFF2A241C),
    NOIR("Sobre", "Fond uni, sans effets", 0xFF0A0A0C, 0xFFF2F2F4),
    CONTRASTE("Contraste", "Lisibilité maximale : noir et blanc", 0xFF000000, 0xFFFFFFFF),
}

enum class MaskStyle(val label: String) { AUTO("Automatique"), LIGHT("Clair"), DARK("Sombre") }

enum class TouchMode(val label: String, val description: String) {
    PROTECT(
        "Protégé",
        "Le défilement passe à travers les masques, comme s'ils n'étaient pas là. Si un appui ouvre une publication masquée, MindFilter la referme aussitôt.",
    ),
    PASSTHROUGH(
        "Traversant",
        "Le défilement passe à travers les masques, et les appuis aussi : un appui sur un masque ouvre normalement la publication située dessous.",
    ),
}

data class CustomFilter(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val keywords: List<String>,
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val enabled: Boolean = true,
)

data class SettingsState(
    val protectionEnabled: Boolean = true,
    val enabledThemes: Set<String> = TaxonomyIndex.defaultEnabledThemeIds,
    val monitoredPackages: Set<String> = DEFAULT_MONITORED,
    val restrictionLevel: Int = RestrictionLevels.DEFAULT,
    val accent: AccentColor = AccentColor.GREEN,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appStyle: AppStyle = AppStyle.VERRE,
    val maskStyle: MaskStyle = MaskStyle.AUTO,
    val touchMode: TouchMode = TouchMode.PROTECT,
    val autoActivation: Boolean = true,
    val customFilters: List<CustomFilter> = emptyList(),
    /** Mots « toujours afficher » : ils annulent les thèmes cochés. */
    val exceptions: List<String> = emptyList(),
) {
    val activeCategoryCount: Int
        get() = TAXONOMY.count { c -> c.allThemes.any { it.id in enabledThemes } }
}

val DEFAULT_MONITORED: Set<String> = setOf(
    "com.facebook.katana",          // Facebook
    "com.facebook.lite",            // Facebook Lite
    "com.instagram.android",        // Instagram
    "com.instagram.barcelona",      // Threads
    "com.android.chrome",           // Chrome
    "com.twitter.android",          // X
    "com.reddit.frontpage",         // Reddit
    "com.google.android.googlequicksearchbox", // Google Discover
    "com.google.android.apps.magazines",       // Google Actualités
    "org.mozilla.firefox",          // Firefox
)

/**
 * Préférences de l'application (stockées localement dans SharedPreferences).
 * L'interface et le service d'accessibilité tournent dans le même processus et
 * observent le même [state].
 */
object AppSettings {
    private const val FILE = "mindfilter_settings"
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Le guide de démarrage doit-il s'afficher ? (première installation, ou demandé dans Réglages) */
    private val _tutorial = MutableStateFlow(false)
    val tutorial: StateFlow<Boolean> = _tutorial.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val freshInstall = !prefs.getBoolean("initialized", false)
        if (freshInstall) {
            save(SettingsState())
            prefs.edit().putBoolean("initialized", true).apply()
        }
        // Guide : seulement pour une toute première installation. Les personnes qui avaient
        // déjà MindFilter avant cette version ne le voient pas (sauf si elles le demandent).
        if (!prefs.contains("tutorialDone")) {
            prefs.edit().putBoolean("tutorialDone", !freshInstall).apply()
        }
        _tutorial.value = !prefs.getBoolean("tutorialDone", true)
        _state.value = load()
    }

    fun finishTutorial() {
        _tutorial.value = false
        prefs.edit().putBoolean("tutorialDone", true).apply()
    }

    fun restartTutorial() {
        _tutorial.value = true
    }

    private fun load(): SettingsState {
        val d = SettingsState()
        return SettingsState(
            protectionEnabled = prefs.getBoolean("protection", d.protectionEnabled),
            enabledThemes = prefs.getStringSet("themes", d.enabledThemes)?.toSet()
                ?.filter { it in TaxonomyIndex.allThemeIds }?.toSet() ?: d.enabledThemes,
            monitoredPackages = prefs.getStringSet("packages", d.monitoredPackages)?.toSet() ?: d.monitoredPackages,
            restrictionLevel = if (prefs.contains("filterLevel")) {
                prefs.getInt("filterLevel", d.restrictionLevel).coerceIn(RestrictionLevels.MIN, RestrictionLevels.MAX)
            } else if (prefs.contains("restrictionLevel")) {
                RestrictionLevels.fromTenSteps(prefs.getInt("restrictionLevel", 7))
            } else {
                // Migration depuis l'ancien réglage à 5 niveaux.
                prefs.getString("sensitivity", null)
                    ?.let { n -> Sensitivity.entries.firstOrNull { it.name == n } }
                    ?.let { RestrictionLevels.fromSensitivity(it) } ?: d.restrictionLevel
            },
            accent = enumOr(prefs.getString("accent", null), d.accent),
            themeMode = enumOr(prefs.getString("themeMode", null), d.themeMode),
            appStyle = prefs.getString("appStyle", null).let { v ->
                // L'ancien thème « Papier » (0.2.9) est fusionné dans « Sobre ».
                if (v == "PAPIER") AppStyle.NOIR else enumOr(v, d.appStyle)
            },
            maskStyle = enumOr(prefs.getString("maskStyle", null), d.maskStyle),
            touchMode = enumOr(prefs.getString("touchMode", null), d.touchMode),
            autoActivation = prefs.getBoolean("autoActivation", d.autoActivation),
            customFilters = parseFilters(prefs.getString("customFilters", null)),
            exceptions = parseStrings(prefs.getString("exceptions", null)),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun save(s: SettingsState) {
        prefs.edit()
            .putBoolean("protection", s.protectionEnabled)
            .putStringSet("themes", HashSet(s.enabledThemes))
            .putStringSet("packages", HashSet(s.monitoredPackages))
            .putInt("filterLevel", s.restrictionLevel)
            .putString("accent", s.accent.name)
            .putString("themeMode", s.themeMode.name)
            .putString("appStyle", s.appStyle.name)
            .putString("maskStyle", s.maskStyle.name)
            .putString("touchMode", s.touchMode.name)
            .putBoolean("autoActivation", s.autoActivation)
            .putString("customFilters", serializeFilters(s.customFilters))
            .putString("exceptions", JSONArray(s.exceptions).toString())
            .apply()
    }

    fun update(transform: (SettingsState) -> SettingsState) {
        val next = transform(_state.value)
        if (next != _state.value) {
            _state.value = next
            save(next)
        }
    }

    // --- Raccourcis --------------------------------------------------------

    fun setProtection(on: Boolean) = update { it.copy(protectionEnabled = on) }

    fun setThemes(ids: Collection<String>, enabled: Boolean) = update {
        it.copy(enabledThemes = if (enabled) it.enabledThemes + ids else it.enabledThemes - ids.toSet())
    }

    fun setPackageMonitored(pkg: String, monitored: Boolean) = update {
        it.copy(monitoredPackages = if (monitored) it.monitoredPackages + pkg else it.monitoredPackages - pkg)
    }

    fun upsertFilter(f: CustomFilter) = update { s ->
        val list = s.customFilters.toMutableList()
        val i = list.indexOfFirst { it.id == f.id }
        if (i >= 0) list[i] = f else list.add(f)
        s.copy(customFilters = list)
    }

    fun addException(word: String) = update { s ->
        val w = word.trim()
        if (w.isEmpty() || s.exceptions.any { it.equals(w, ignoreCase = true) }) s
        else s.copy(exceptions = s.exceptions + w)
    }

    fun removeException(word: String) = update { s -> s.copy(exceptions = s.exceptions - word) }

    private fun parseStrings(json: String?): List<String> = try {
        if (json.isNullOrBlank()) emptyList()
        else JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (e: Exception) {
        emptyList()
    }

    fun deleteFilter(id: String) = update { s -> s.copy(customFilters = s.customFilters.filterNot { it.id == id }) }

    // --- Démarrage du téléphone -------------------------------------------

    /** Renvoie le compteur de démarrages précédemment mémorisé et enregistre le nouveau. */
    fun swapBootCount(current: Int): Int {
        val previous = prefs.getInt("lastBootCount", Int.MIN_VALUE)
        prefs.edit().putInt("lastBootCount", current).apply()
        return previous
    }

    // --- Sérialisation des filtres personnalisés --------------------------

    private fun serializeFilters(list: List<CustomFilter>): String {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("name", f.name)
                    .put("keywords", JSONArray(f.keywords))
                    .put("sensitivity", f.sensitivity.name)
                    .put("enabled", f.enabled),
            )
        }
        return arr.toString()
    }

    private fun parseFilters(json: String?): List<CustomFilter> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val kw = o.optJSONArray("keywords") ?: JSONArray()
                CustomFilter(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Filtre"),
                    keywords = (0 until kw.length()).map { kw.getString(it) },
                    sensitivity = enumOr(o.optString("sensitivity"), Sensitivity.NORMAL),
                    enabled = o.optBoolean("enabled", true),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** État d'exécution du service (non persistant). */
object ServiceStatus {
    val connected = MutableStateFlow(false)
    val maskedThisSession = MutableStateFlow(0)
    val currentlyMasked = MutableStateFlow(0)
}
