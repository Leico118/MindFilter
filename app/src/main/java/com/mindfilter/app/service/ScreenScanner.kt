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
    /** Cet élément EST une image reconnue comme à masquer (masquée en plus de sa publication). */
    var imageFlag: String? = null
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
    /** Mots qui ont déclenché le masquage (masquage par le texte). */
    val words: List<String> = emptyList(),
    /** Origine : [KIND_TEXT], [KIND_IMAGE] ou [KIND_PENDING] (image en cours d'analyse). */
    val kind: Int = KIND_TEXT,
) {
    companion object {
        const val KIND_TEXT = 0
        const val KIND_IMAGE = 1
        const val KIND_PENDING = 2
    }
}

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
        addFlaggedImages(root, screen, out, usedKeys)
        return out
    }

    /**
     * Chaque image reconnue est aussi masquée elle-même : même si l'appli range l'image à côté
     * du bloc de texte (et non dedans), elle est couverte.
     */
    private fun addFlaggedImages(n: SNode, clip: Rect, out: MutableList<MaskTarget>, usedKeys: MutableSet<String>) {
        val childClip = Rect(clip)
        if (n.scrollable && !childClip.intersect(n.bounds)) return
        val label = n.imageFlag
        if (label != null) {
            val visible = Rect(n.bounds)
            if (visible.intersect(clip)) {
                val covered = out.any { t ->
                    val r = Rect(t.rect)
                    r.intersect(t.clip) && r.contains(visible.centerX(), visible.centerY())
                }
                if (!covered) add(n, clip, label, out, usedKeys, kind = if (label == PENDING_LABEL) MaskTarget.KIND_PENDING else MaskTarget.KIND_IMAGE)
            }
        }
        for (c in n.children) addFlaggedImages(c, childClip, out, usedKeys)
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
            // Liste de lignes semblables (historique de recherche, liste de groupes, menu…) :
            // chaque ligne est indépendante. On ne masque que les lignes concernées, et rien si
            // aucune ligne ne l'est à elle seule (des mots de lignes différentes ne doivent pas
            // se combiner).
            val list = findRowList(n)
            if (list != null && n.flagLabel == null) {
                var any = false
                for (c in list.children) {
                    if (c.fullText.isBlank()) continue
                    if (classifier.classify(c.fullText) != null && visit(c, childClip, classifier, maxHeight, out, usedKeys)) any = true
                }
                return any
            }
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
            add(n, clip, match.label, out, usedKeys, match.words, if (match.id == "image") MaskTarget.KIND_IMAGE else MaskTarget.KIND_TEXT)
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
                add(c, childClip, match.label, out, usedKeys, match.words)
                added = true
            }
        }
        return added
    }

    /**
     * Cherche dans [n] (lui-même ou un descendant proche qui porte l'essentiel du texte) une
     * liste de lignes semblables : au moins 4 lignes de texte de même type et de hauteur
     * voisine, sans grande image. Une publication (en-tête, texte, photo, boutons) n'en est pas une.
     */
    internal fun findRowList(n: SNode, depth: Int = 0): SNode? {
        if (depth > 3) return null
        val textKids = n.children.filter { it.fullText.isNotBlank() }
        if (textKids.size >= 4) {
            val heights = textKids.map { it.bounds.height() }.sorted()
            val med = heights[heights.size / 2].coerceAtLeast(1)
            val mainClass = textKids.groupingBy { it.className }.eachCount().maxByOrNull { it.value }?.key
            val rows = textKids.count { it.className == mainClass && it.bounds.height() in (med / 2)..(med * 2) }
            val bigImage = n.children.any { it.fullText.isBlank() && it.bounds.height() >= n.bounds.height() / 4 }
            if (rows >= 4 && rows * 10 >= textKids.size * 7 && !bigImage) return n
        }
        // Descendre dans l'enfant qui porte l'essentiel du texte (le titre « Récentes » à côté).
        val total = n.fullText.length.coerceAtLeast(1)
        val main = textKids.maxByOrNull { it.fullText.length } ?: return null
        if (main.fullText.length * 10 < total * 6) return null
        return findRowList(main, depth + 1)
    }

    /** [zone] = publication entière dont l'appli ne signale pas l'image (Reddit…) : l'image
     *  sera cherchée directement dans la capture. */
    class ImageCandidate(val key: String, val node: AccessibilityNodeInfo, val bounds: Rect, val zone: Boolean = false)

    /**
     * Repère les images visibles (éléments sans texte, assez grands) et marque les blocs dont
     * une image a déjà été reconnue comme à masquer. Une image est identifiée par l'élément qui
     * l'affiche + le début du texte de sa publication (auteur, première ligne) : si l'appli
     * réutilise le cadre pour une autre publication, ce n'est plus la même clé.
     */
    const val PENDING_LABEL = "Analyse de l'image…"

    fun markImages(
        root: SNode,
        screen: Rect,
        maxHeight: Int,
        lookup: (String) -> String?,
        /** Cacher cette image (pas encore jugée) pendant son analyse ? */
        precaution: (String) -> Boolean = { false },
    ): List<ImageCandidate> {
        val out = ArrayList<ImageCandidate>()
        val minW = screen.width() * 3 / 10
        val minH = screen.width() / 5
        fun walk(n: SNode, context: String): String? {
            val isPost = context.isEmpty() && n.bounds.height() <= maxHeight && n.fullText.isNotBlank()
            val ctx = if (isPost) n.fullText.take(40) else context
            val before = out.size
            var flag: String? = null
            val info = n.info
            // Image = élément assez grand, sans enfant porteur de texte. Il peut avoir une courte
            // description (Facebook écrit « Peut être une image de… » sur ses photos), mais ce
            // ne doit pas être un bloc de texte, un bouton ou un champ de saisie.
            val cls = n.className.lowercase()
            val textual = "text" in cls || "button" in cls || "edit" in cls
            val looksLikeImage = info != null && !n.offscreen && !textual &&
                ("image" in cls || n.ownText.length <= 250) &&
                n.children.none { it.fullText.isNotBlank() && !it.className.lowercase().contains("image") } &&
                n.bounds.width() >= minW && n.bounds.height() >= minH
            if (looksLikeImage && info != null) {
                // Identité STABLE de l'image : l'élément qui l'affiche + sa description (différente
                // pour chaque photo sur Facebook) + sa largeur. Le texte de la publication n'en fait
                // plus partie : il change pendant le défilement (en-tête qui sort de l'écran…), ce
                // qui faisait disparaître puis réapparaître les masques.
                val key = "${info.hashCode()}|${n.ownText.take(80).hashCode()}|${n.bounds.width() / 16}"
                val label = lookup(key)
                if (label != null) {
                    flag = label
                    n.imageFlag = label
                } else {
                    out.add(ImageCandidate(key, info, Rect(n.bounds)))
                    // Image encore inconnue : cachée le temps de l'analyse (sans marquer la publication).
                    // Pas pour les vidéos plein écran (Reels, Stories) : on ne cache pas tout l'écran
                    // à chaque nouvelle vidéo.
                    if (n.bounds.height() < screen.height() * 6 / 10 && precaution(key)) n.imageFlag = PENDING_LABEL
                }
            }
            for (c in n.children) {
                val f = walk(c, ctx)
                if (flag == null && f != null) flag = f
            }
            // Certaines applis (Reddit, applis en Jetpack Compose…) ne décrivent pas leurs images
            // comme des éléments séparés. Si une publication assez grande ne contient aucune image
            // repérée, on la propose entière : l'analyse cherchera l'image dans la capture.
            if (isPost && flag == null && out.size == before && info != null && !n.offscreen &&
                n.bounds.width() >= screen.width() * 6 / 10 && n.bounds.height() >= screen.width() * 4 / 10
            ) {
                val key = "zone|${info.hashCode()}|${n.bounds.width() / 16}"
                val label = lookup(key)
                if (label != null) flag = label else out.add(ImageCandidate(key, info, Rect(n.bounds), zone = true))
            }
            n.flagLabel = flag
            return flag
        }
        walk(root, "")
        return out
    }

    private fun add(
        n: SNode,
        clip: Rect,
        label: String,
        out: MutableList<MaskTarget>,
        usedKeys: MutableSet<String>,
        words: List<String> = emptyList(),
        kind: Int = MaskTarget.KIND_TEXT,
    ) {
        val visible = Rect(n.bounds)
        if (!visible.intersect(clip)) {
            // Publication préchargée hors de l'écran : masque préparé (invisible pour l'instant),
            // il suivra le défilement et sera déjà en place quand elle apparaîtra.
            if (!n.offscreen || n.bounds.height() < 24 || n.bounds.width() < 48) return
            var key = n.className + "|" + n.fullText.take(160).hashCode()
            var i = 1
            while (!usedKeys.add(key)) key = key.substringBefore('#') + "#" + i++
            out.add(MaskTarget(key, Rect(n.bounds), Rect(clip), label, n.info, words, kind))
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
        out.add(MaskTarget(key, full, Rect(clip), label, n.info, words, kind))
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
