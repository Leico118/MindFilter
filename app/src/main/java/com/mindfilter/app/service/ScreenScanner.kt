package com.mindfilter.app.service

import android.graphics.Rect
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
}

data class MaskTarget(val key: String, val rect: Rect, val label: String)

/**
 * Parcourt l'arbre d'accessibilité de l'application au premier plan et décide
 * quelles zones masquer. Les champs de saisie et les mots de passe sont ignorés.
 */
object ScreenScanner {
    private const val MAX_NODES = 2500
    private const val MAX_DEPTH = 60
    private const val MAX_TEXT = 1200

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
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            build(child, depth + 1, counter)?.let { s.children.add(it) }
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

    private fun visit(
        n: SNode,
        clip: Rect,
        classifier: ContentClassifier,
        maxHeight: Int,
        out: MutableList<MaskTarget>,
        usedKeys: MutableSet<String>,
    ) {
        if (n.fullText.isBlank() || out.size >= 40) return
        val match = classifier.classify(n.fullText) ?: return

        val childClip = Rect(clip)
        if (n.scrollable && !childClip.intersect(n.bounds)) return

        val tooBig = n.scrollable || n.bounds.height() > maxHeight || n.bounds.width() <= 0
        if (!tooBig) {
            add(n, clip, match.label, out, usedKeys)
            return
        }
        val matching = n.children.filter { it.fullText.isNotBlank() && classifier.classify(it.fullText) != null }
        if (matching.isNotEmpty()) {
            matching.forEach { visit(it, childClip, classifier, maxHeight, out, usedKeys) }
        } else {
            // Le sujet n'apparaît qu'en combinant plusieurs blocs : on masque ces blocs.
            n.children.filter { it.fullText.isNotBlank() && it.bounds.height() <= maxHeight }
                .forEach { add(it, childClip, match.label, out, usedKeys) }
        }
    }

    private fun add(n: SNode, clip: Rect, label: String, out: MutableList<MaskTarget>, usedKeys: MutableSet<String>) {
        val r = Rect(n.bounds)
        if (!r.intersect(clip)) return
        if (r.height() < 24 || r.width() < 48) return
        var key = n.className + "|" + n.fullText.take(160).hashCode()
        var i = 1
        while (!usedKeys.add(key)) key = key.substringBefore('#') + "#" + i++
        out.add(MaskTarget(key, r, label))
    }
}
