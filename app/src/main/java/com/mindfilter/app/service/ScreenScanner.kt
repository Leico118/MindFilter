package com.mindfilter.app.service

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.mindfilter.app.engine.ContentClassifier

/** Copie légère d'un nœud d'accessibilité (rien n'est conservé après l'analyse). */
class SNode(
    val bounds: Rect,
    val scrollable: Boolean,
    val ownText: String,
    val className: String,
) {
    val children = ArrayList<SNode>(4)
    var fullText: String = ""
    /** Ce nœud ou un de ses descendants est une liste qui défile. */
    var hasScrollable: Boolean = scrollable
}

/**
 * [rect] : zone complète estimée de la publication (peut dépasser du bord de l'écran) ;
 * [clip] : zone visible de la liste qui la contient. Le masque affiché = rect ∩ clip.
 */
data class MaskTarget(val key: String, val rect: Rect, val clip: Rect, val label: String)

/**
 * Parcourt l'arbre d'accessibilité de l'application au premier plan et décide
 * quelles zones masquer. Les champs de saisie et les mots de passe sont ignorés.
 */
object ScreenScanner {
    private const val MAX_NODES = 2500
    private const val MAX_DEPTH = 60
    private const val MAX_TEXT = 1500

    fun snapshot(root: AccessibilityNodeInfo): SNode? {
        val counter = intArrayOf(0)
        return build(root, 0, counter)
    }

    private fun build(node: AccessibilityNodeInfo, depth: Int, counter: IntArray): SNode? {
        if (counter[0] >= MAX_NODES || depth > MAX_DEPTH) return null
        counter[0]++
        if (!node.isVisibleToUser) return null
        if (node.isPassword || node.isEditable) return null // confidentialité : jamais lus

        val r = Rect()
        node.getBoundsInScreen(r)
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val own = when {
            text.isEmpty() -> desc
            desc.isEmpty() || desc == text || text.contains(desc) -> text
            desc.contains(text) -> desc
            else -> "$text $desc"
        }
        val s = SNode(r, node.isScrollable, own, node.className?.toString().orEmpty())
        for (i in 0 until node.childCount) {
            val child = try {
                // Android 13+ : on demande à Android d'envoyer d'un coup les nœuds suivants
                // dans l'ordre où on les lit (beaucoup moins d'allers-retours avec l'appli).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST)
                } else {
                    node.getChild(i)
                }
            } catch (e: Exception) {
                null
            } ?: continue
            build(child, depth + 1, counter)?.let {
                s.children.add(it)
                if (it.hasScrollable) s.hasScrollable = true
            }
        }
        // Texte complet du sous-arbre (limité) calculé de bas en haut.
        val sb = StringBuilder(s.ownText)
        for (c in s.children) {
            if (sb.length >= MAX_TEXT) break
            if (c.fullText.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(" · ")
                sb.append(c.fullText)
            }
        }
        s.fullText = if (sb.length > MAX_TEXT) sb.substring(0, MAX_TEXT) else sb.toString()
        return s
    }

    /**
     * Cherche, pour chaque contenu détecté, le plus grand conteneur qui ressemble à
     * une publication (élément de liste, carte) sans dépasser [maxHeight].
     */
    fun findTargets(root: SNode, classifier: ContentClassifier, screen: Rect, maxHeight: Int): List<MaskTarget> {
        val out = ArrayList<MaskTarget>()
        val usedKeys = HashSet<String>()
        visit(root, screen, classifier, maxHeight, out, usedKeys)
        return out
    }

    /**
     * Parcourt TOUT l'écran : chaque publication est analysée séparément (avant, l'analyse
     * partait du texte global de l'écran, limité en taille, et pouvait rater les publications
     * situées plus bas). Les résultats sont mémorisés : une publication déjà analysée ne
     * l'est plus pendant le défilement. Renvoie vrai si au moins un masque a été ajouté.
     */
    private fun visit(
        n: SNode,
        clip: Rect,
        classifier: ContentClassifier,
        maxHeight: Int,
        out: MutableList<MaskTarget>,
        usedKeys: MutableSet<String>,
    ): Boolean {
        if (n.fullText.isBlank() || out.size >= 40) return false
        val childClip = Rect(clip)
        if (n.scrollable && !childClip.intersect(n.bounds)) return false

        val tooBig = n.scrollable || n.bounds.height() > maxHeight || n.bounds.width() <= 0
        if (!tooBig) {
            val match = classifier.classify(n.fullText) ?: return false
            add(n, clip, match.label, out, usedKeys)
            return true
        }
        var any = false
        for (c in n.children) if (visit(c, childClip, classifier, maxHeight, out, usedKeys)) any = true
        if (any || n.hasScrollable) return any
        // Grand bloc sans liste (ex. article ouvert) : le sujet n'apparaît qu'en combinant
        // plusieurs paragraphes. On masque alors ces paragraphes.
        val match = classifier.classify(n.fullText) ?: return false
        var added = false
        for (c in n.children) {
            if (c.fullText.isNotBlank() && c.bounds.height() <= maxHeight) {
                add(c, childClip, match.label, out, usedKeys)
                added = true
            }
        }
        return added
    }

    private fun add(n: SNode, clip: Rect, label: String, out: MutableList<MaskTarget>, usedKeys: MutableSet<String>) {
        val visible = Rect(n.bounds)
        if (!visible.intersect(clip)) return
        if (visible.height() < 24 || visible.width() < 48) return
        // Android ne fournit que la partie visible d'une publication coupée par le bord de
        // la liste. On prolonge le masque au-delà de ce bord : quand la publication entre
        // dans l'écran en défilant, elle est déjà entièrement couverte.
        val full = Rect(n.bounds)
        val extra = clip.height()
        if (full.bottom >= clip.bottom - 3) full.bottom += extra
        if (full.top <= clip.top + 3) full.top -= extra
        var key = n.className + "|" + n.fullText.take(160).hashCode()
        var i = 1
        while (!usedKeys.add(key)) key = key.substringBefore('#') + "#" + i++
        out.add(MaskTarget(key, full, Rect(clip), label))
    }
}
