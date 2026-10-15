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

enum class ThemeMode(val label: String) { SYSTEM("Automatique"), LIGHT("Clair"), DARK("Sombre") }

enum class MaskStyle(val label: String) { AUTO("Automatique"), LIGHT("Clair"), DARK("Sombre") }

enum class TouchMode(val label: String, val description: String) {
    PROTECT(
        "Protégé",
        "Un appui sur un masque ne fait rien : la publication ne peut pas être ouverte. Un glissement sur un masque est relayé à l'application pour faire défiler la page.",
    ),
    PASSTHROUGH(
        "Fluide",
        "Les masques laissent passer tous les gestes : défilement parfaitement fluide, mais un appui sur un masque ouvre la publication située dessous.",
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
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val maskStyle: MaskStyle = MaskStyle.AUTO,
    val touchMode: TouchMode = TouchMode.PROTECT,
    val autoActivation: Boolean = true,
    val customFilters: List<CustomFilter> = emptyList(),
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

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("initialized", false)) {
            save(SettingsState())
            prefs.edit().putBoolean("initialized", true).apply()
        }
        _state.value = load()
    }

    private fun load(): SettingsState {
        val d = SettingsState()
        return SettingsState(
            protectionEnabled = prefs.getBoolean("protection", d.protectionEnabled),
            enabledThemes = prefs.getStringSet("themes", d.enabledThemes)?.toSet()
                ?.filter { it in TaxonomyIndex.allThemeIds }?.toSet() ?: d.enabledThemes,
            monitoredPackages = prefs.getStringSet("packages", d.monitoredPackages)?.toSet() ?: d.monitoredPackages,
            sensitivity = enumOr(prefs.getString("sensitivity", null), d.sensitivity),
            themeMode = enumOr(prefs.getString("themeMode", null), d.themeMode),
            maskStyle = enumOr(prefs.getString("maskStyle", null), d.maskStyle),
            touchMode = enumOr(prefs.getString("touchMode", null), d.touchMode),
            autoActivation = prefs.getBoolean("autoActivation", d.autoActivation),
            customFilters = parseFilters(prefs.getString("customFilters", null)),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun save(s: SettingsState) {
        prefs.edit()
            .putBoolean("protection", s.protectionEnabled)
            .putStringSet("themes", HashSet(s.enabledThemes))
            .putStringSet("packages", HashSet(s.monitoredPackages))
            .putString("sensitivity", s.sensitivity.name)
            .putString("themeMode", s.themeMode.name)
            .putString("maskStyle", s.maskStyle.name)
            .putString("touchMode", s.touchMode.name)
            .putBoolean("autoActivation", s.autoActivation)
            .putString("customFilters", serializeFilters(s.customFilters))
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
