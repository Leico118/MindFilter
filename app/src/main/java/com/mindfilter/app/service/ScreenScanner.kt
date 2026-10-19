package com.mindfilter.app.service

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.mindfilter.app.engine.ContentClassifier
import com.mindfilter.app.engine.MatchResult

/** Copie légère d'un nœud d'accessibilité (rien n'est conservé après l'analyse). */
class SNode(
    val bounds: Rect,
    val scrollable: Boolean,
    val ownText: String,
    val className: String,
    /** Référence vers l'élément réel, pour suivre sa position en direct (aucun texte relu). */
    val info: AccessibilityNodeInfo? = null,
) {
    val children = ArrayList<SNode>(4)
    var fullText: String = ""
    /** Nœud déjà chargé par l'appli mais pas encore affiché (au-dessus/au-dessous de l'écran). */
    var offscreen: Boolean = false
    /** Une image de ce bloc a été reconnue comme à masquer (option « Analyse des images »). */
    var flagLabel: String? = null
    /** Ce nœud ou un de ses descendants est une liste qui défile. */
    var hasScrollable: Boolean = scrollable
}

/**
 * [rect] : zone complète estimée de la publication (peut dépasser du bord de l'écran) ;
 * [clip] : zone visible de la liste qui la contient. Le masque affiché = rect ∩ clip.
 */
data class MaskTarget(
    val key: String,
    val rect: Rect,
    val clip: Rect,
    val label: String,
    val node: AccessibilityNodeInfo? = null,
)

/**
 * Parcourt l'arbre d'accessibilité de l'application au premier plan et décide
 * quelles zones masquer. Les champs de saisie et les mots de passe sont ignorés.
 */
object ScreenScanner {
    private const val MAX_NODES = 3500
    /** Distance (en hauteurs/largeurs d'écran) jusqu'à laquelle on lit le contenu préchargé. */
    private const val AHEAD_SCREENS = 1.5f
    private const val MAX_DEPTH = 60
    private const val MAX_TEXT = 1500

    /**
     * Copie l'arbre de l'écran. Les éléments que l'appli a DÉJÀ chargés mais qui ne sont pas
     * encore affichés (juste au-dessus ou au-dessous de l'écran) sont aussi lus, jusqu'à
     * [AHEAD_SCREENS] écrans de distance : leurs masques sont préparés à l'avance, invisibles,
     * et apparaissent au moment exact où la publication entre dans l'écran.
     */
    fun snapshot(root: AccessibilityNodeInfo, screen: Rect): SNode? {
        val counter = intArrayOf(0)
        val dx = (screen.width() * AHEAD_SCREENS).toInt()
        val dy = (screen.height() * AHEAD_SCREENS).toInt()
        val ahead = Rect(screen.left - dx, screen.top - dy, screen.right + dx, screen.bottom + dy)
        return build(root, 0, counter, ahead)
    }

    private fun build(node: AccessibilityNodeInfo, depth: Int, counter: IntArray, ahead: Rect): SNode? {
        if (counter[0] >= MAX_NODES || depth > MAX_DEPTH) return null
        counter[0]++
        if (node.isPassword || node.isEditable) return null // confidentialité : jamais lus

        val r = Rect()
        node.getBoundsInScreen(r)
        val visible = node.isVisibleToUser
        // Hors écran : gardé seulement si Android donne sa vraie position et qu'il est proche.
        if (!visible && (r.isEmpty || !Rect.intersects(r, ahead))) return null
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val own = when {
            text.isEmpty() -> desc
            desc.isEmpty() || desc == text || text.contains(desc) -> text
            desc.contains(text) -> desc
            else -> "$text $desc"
        }
        val s = SNode(r, node.isScrollable, own, node.className?.toString().orEmpty(), node)
        s.offscreen = !visible
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
            build(child, depth + 1, counter, ahead)?.let {
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
        if ((n.fullText.isBlank() && n.flagLabel == null) || out.size >= 60) return false
        val childClip = Rect(clip)
        if (n.scrollable && !childClip.intersect(n.bounds)) return false

        // Un élément qui touche à la fois le haut ET le bas de la liste visible et en occupe
        // l'essentiel n'est pas une publication mais un conteneur de plusieurs publications
        // (cas de la recherche Facebook sur certains téléphones) : on regarde à l'intérieur.
        val fillsList = n.bounds.top <= clip.top + 4 && n.bounds.bottom >= clip.bottom - 4 &&
            n.bounds.height() >= clip.height() * 6 / 10 && n.children.isNotEmpty()
        val tooBig = n.scrollable || fillsList || n.bounds.height() > maxHeight || n.bounds.width() <= 0
        if (!tooBig) {
            val match = classifier.classify(n.fullText)
                ?: n.flagLabel?.let { MatchResult("image", it, 4) }
                ?: return false
            // Grand bloc qui contient PLUSIEURS éléments concernés chacun de son côté : c'est
            // une liste de publications (ex. résultats de recherche), pas une seule publication.
            // On masque alors chaque élément séparément plutôt que tout le bloc.
            if (n.bounds.height() > clip.height() * 4 / 10) {
                // On traverse les simples « emballages » (un seul enfant avec du texte).
                var inner = n
                while (true) {
                    val withText = inner.children.filter { it.fullText.isNotBlank() }
                    if (withText.size != 1) break
                    inner = withText[0]
                }
                val hits = inner.children.count { it.fullText.isNotBlank() && classifier.classify(it.fullText) != null }
                if (hits >= 2) {
                    var any = false
                    for (c in inner.children) if (visit(c, childClip, classifier, maxHeight, out, usedKeys)) any = true
                    if (any) return true
                }
            }
            add(n, clip, match.label, out, usedKeys)
            return true
        }
        var any = false
        for (c in n.children) if (visit(c, childClip, classifier, maxHeight, out, usedKeys)) any = true
        // Pas de regroupement pour un conteneur de plusieurs publications : on ne masque pas
        // tout un écran parce que des mots de publications différentes se combinent.
        if (any || n.hasScrollable || fillsList) return any
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

    class ImageCandidate(val key: String, val node: AccessibilityNodeInfo, val bounds: Rect)

    /**
     * Repère les images visibles (éléments sans texte, assez grands) et marque les blocs dont
     * une image a déjà été reconnue comme à masquer. Une image est identifiée par l'élément qui
     * l'affiche + le début du texte de sa publication (auteur, première ligne) : si l'appli
     * réutilise le cadre pour une autre publication, ce n'est plus la même clé.
     */
    fun markImages(root: SNode, screen: Rect, maxHeight: Int, lookup: (String) -> String?): List<ImageCandidate> {
        val out = ArrayList<ImageCandidate>()
        val minW = screen.width() * 3 / 10
        val minH = screen.width() / 5
        fun walk(n: SNode, context: String): String? {
            val ctx = if (context.isEmpty() && n.bounds.height() <= maxHeight && n.fullText.isNotBlank()) n.fullText.take(60) else context
            var flag: String? = null
            val info = n.info
            val looksLikeImage = info != null && !n.offscreen && n.ownText.isBlank() &&
                n.children.none { it.fullText.isNotBlank() } &&
                n.bounds.width() >= minW && n.bounds.height() >= minH
            if (looksLikeImage && info != null) {
                val key = "${info.hashCode()}|${ctx.hashCode()}"
                val label = lookup(key)
                if (label != null) flag = label else out.add(ImageCandidate(key, info, Rect(n.bounds)))
            }
            for (c in n.children) {
                val f = walk(c, ctx)
                if (flag == null && f != null) flag = f
            }
            n.flagLabel = flag
            return flag
        }
        walk(root, "")
        return out
    }

    private fun add(n: SNode, clip: Rect, label: String, out: MutableList<MaskTarget>, usedKeys: MutableSet<String>) {
        val visible = Rect(n.bounds)
        if (!visible.intersect(clip)) {
            // Publication préchargée hors de l'écran : masque préparé (invisible pour l'instant),
            // il suivra le défilement et sera déjà en place quand elle apparaîtra.
            if (!n.offscreen || n.bounds.height() < 24 || n.bounds.width() < 48) return
            var key = n.className + "|" + n.fullText.take(160).hashCode()
            var i = 1
            while (!usedKeys.add(key)) key = key.substringBefore('#') + "#" + i++
            out.add(MaskTarget(key, Rect(n.bounds), Rect(clip), label, n.info))
            return
        }
        if (visible.height() < 24 || visible.width() < 48) return
        // Android ne fournit que la partie visible d'une publication coupée par le bord de
        // la liste. On prolonge le masque au-delà de ce bord : quand la publication entre
        // dans l'écran en défilant, elle est déjà entièrement couverte.
        val full = maskRect(n.bounds, clip)
        var key = n.className + "|" + n.fullText.take(160).hashCode()
        var i = 1
        while (!usedKeys.add(key)) key = key.substringBefore('#') + "#" + i++
        out.add(MaskTarget(key, full, Rect(clip), label, n.info))
    }

    /**
     * Zone du masque à partir de la position d'un élément : s'il touche un bord de la liste
     * (publication coupée), on prolonge au-delà du bord pour couvrir la partie encore cachée.
     */
    fun maskRect(bounds: Rect, clip: Rect): Rect {
        val full = Rect(bounds)
        if (!Rect.intersects(bounds, clip)) return full
        val extra = clip.height()
        if (full.bottom >= clip.bottom - 3) full.bottom += extra
        if (full.top <= clip.top + 3) full.top -= extra
        return full
    }
}
