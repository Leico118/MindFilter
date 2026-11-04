package com.mindfilter.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Journal « Pourquoi c'est masqué ? » : les derniers masquages, avec leur raison.
 *
 * Seuls sont gardés : l'heure, l'appli, le thème, les MOTS repérés (jamais le texte de la
 * publication) ou, pour une image, la méthode de détection et une empreinte (pas l'image).
 * Le journal reste en mémoire : il s'efface au redémarrage du téléphone ou du service.
 */
object MaskLog {
    enum class Kind { TEXT, IMAGE }

    class Entry(
        val id: Long,
        val time: Long,
        val app: String,
        val theme: String,
        val kind: Kind,
        /** Mots repérés (texte, ou texte lu dans l'image). */
        val words: List<String>,
        /** Méthode de détection d'une image (ex. « détecteur de contenus adultes : 72 % »). */
        val detail: String,
        /** Empreinte visuelle de l'image (pour « Cette image n'est pas à masquer »). */
        val imageHash: Long? = null,
        /** Empreinte de ressemblance (pour apprendre un exemple « sans problème »). */
        val vector: FloatArray? = null,
    )

    private const val MAX = 40

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** Corrections déjà faites (id des lignes), pour griser le bouton. */
    val corrected = MutableStateFlow<Set<Long>>(emptySet())

    private var nextId = 1L
    /** Clés des masques déjà notés : une même publication n'est notée qu'une fois. */
    private val seenKeys = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 300
    }

    @Volatile var currentApp: String = ""

    fun logText(key: String, theme: String, words: List<String>) {
        synchronized(this) {
            if (seenKeys.containsKey(key)) return
            seenKeys[key] = System.currentTimeMillis()
        }
        push(Kind.TEXT, theme, words, "", null, null)
    }

    fun logImage(key: String, theme: String, detail: String, words: List<String>, hash: Long?, vector: FloatArray?) {
        synchronized(this) {
            if (seenKeys.containsKey("img|$key")) return
            seenKeys["img|$key"] = System.currentTimeMillis()
        }
        push(Kind.IMAGE, theme, words, detail, hash, vector)
    }

    private fun push(kind: Kind, theme: String, words: List<String>, detail: String, hash: Long?, vector: FloatArray?) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val list = _entries.value
            // Même raison dans la même appli il y a moins de 20 s (publication qui revient) : on
            // ne la répète pas.
            val dup = list.firstOrNull {
                it.kind == kind && it.theme == theme && it.words == words && it.app == currentApp &&
                    it.detail == detail && now - it.time < 20_000 && (hash == null || it.imageHash == hash)
            }
            if (dup != null) return
            val e = Entry(nextId++, now, currentApp, theme, kind, words, detail, hash, vector)
            _entries.value = (listOf(e) + list).take(MAX)
        }
    }

    fun markCorrected(id: Long) {
        corrected.value = corrected.value + id
    }

    fun clear() {
        synchronized(this) {
            _entries.value = emptyList()
            seenKeys.clear()
        }
        corrected.value = emptySet()
    }
}
