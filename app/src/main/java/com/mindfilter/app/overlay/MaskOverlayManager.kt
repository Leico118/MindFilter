package com.mindfilter.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.mindfilter.app.service.MaskTarget

/**
 * Affiche tous les masques dans UNE SEULE fenêtre de superposition d'accessibilité,
 * plein écran et totalement traversable (FLAG_NOT_TOUCHABLE).
 *
 * Avant, chaque masque avait sa propre fenêtre : pour le suivre pendant le défilement,
 * Android devait déplacer chaque fenêtre (opération lente, gérée par le système).
 * Désormais, déplacer un masque revient à changer sa position à l'intérieur de notre
 * fenêtre : c'est instantané et calculé directement par le processeur graphique.
 *
 * TYPE_ACCESSIBILITY_OVERLAY ne nécessite pas l'autorisation « afficher par-dessus ».
 * Toutes les méthodes doivent être appelées sur le thread principal.
 */
class MaskOverlayManager(
    private val context: Context,
    private val relay: DragRelay,
) {
    private class Entry(val view: MaskView, val rect: Rect, val clip: Rect) {
        /** Moment (uptime) de la dernière position lue en direct par le suivi. */
        var trackedAt = 0L
        /** Masque provisoire (image en cours d'analyse) : pas compté comme masquage. */
        var pending = false
        /** Bouclier qui absorbe les appuis (mode « Bloqué »), dans sa propre petite fenêtre. */
        var shield: ShieldView? = null
        var shieldLp: WindowManager.LayoutParams? = null
        /** Fenêtre floutée du style « Verre » (Android 12 et plus). */
        var glass: Any? = null
        /** Thème qui a déclenché le masque (affiché par le bouton « i »). */
        var label: String = ""
        /** Petite zone tactile posée sur le bouton « i » (le reste du masque reste traversable). */
        var info: View? = null
        var infoLp: WindowManager.LayoutParams? = null
    }

    /** Appui sur le « i » d'un masque : la catégorie s'affiche à la place de « Masqué ». */
    private fun showInfo(e: Entry) {
        val text = com.mindfilter.app.data.TaxonomyIndex.describe(e.label.ifBlank { "Contenu masqué" })
        e.view.showInfo(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (e.glass as? GlassWindow)?.showInfo(text)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun syncInfo(e: Entry, shown: Rect) {
        val size = (44 * context.resources.displayMetrics.density).toInt()
        // En mode « Bloqué », le bouclier gère lui-même l'appui sur le « i ».
        if (shieldsOn || e.pending || e.view.visibility != View.VISIBLE || !e.view.hasInfoBadge() ||
            shown.width() < size * 2 || shown.height() < size
        ) {
            removeInfo(e)
            return
        }
        val lp = e.infoLp ?: newShieldLp().also { e.infoLp = it }
        lp.x = shown.right - size
        lp.y = shown.bottom - size
        lp.width = size
        lp.height = size
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            val v = e.info
            if (v == null) {
                val iv = View(context)
                iv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                iv.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) showInfo(e)
                    true
                }
                wm.addView(iv, lp)
                e.info = iv
            } else {
                wm.updateViewLayout(v, lp)
            }
        } catch (_: Exception) {
            removeInfo(e)
        }
    }

    private fun removeInfo(e: Entry) {
        val v = e.info ?: return
        e.info = null
        e.infoLp = null
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }

    // --- Style « Couleur » ---------------------------------------------------------------------
    private var hue: Float? = null

    /** Teinte des masques (style « Couleur »), ou null pour clair / sombre / verre. */
    fun setHue(value: Float?) {
        if (value == hue) return
        hue = value
        entries.values.forEach { it.view.setHue(value) }
    }

    // --- Style « Verre » ----------------------------------------------------------------------
    private var glassOn = false
    // Le flou « officiel » d'Android (fenêtres floutées) n'est plus utilisé : il ne marchait pas
    // sur tous les téléphones, et s'il échouait sans prévenir, le contenu restait visible à
    // travers le voile. Le verre est désormais fait maison (voir applyBackdrop).
    private val glassSupported: Boolean = false
    private var fakeGlass = false

    /** Style « Verre » fait maison activé ou non. */
    fun setFakeGlass(on: Boolean) {
        if (on == fakeGlass) return
        fakeGlass = on
        entries.values.forEach { it.view.setFakeGlass(on) }
    }

    /**
     * Nouvelle capture de l'appli (en mémoire, effacée aussitôt) : chaque masque reçoit une
     * version minuscule (1/32) de ce qu'il couvre, agrandie en douceur : il ne reste que des
     * taches de couleur, rien n'est lisible. [originX]/[originY] = position de la fenêtre capturée.
     */
    fun applyBackdrop(shot: android.graphics.Bitmap, originX: Int, originY: Int) {
        if (!fakeGlass) return
        for (e in entries.values) {
            val r = visiblePart(e.rect, e.clip)
            r.offset(-originX, -originY)
            if (!r.intersect(0, 0, shot.width, shot.height) || r.width() < 8 || r.height() < 8) continue
            try {
                val tw = maxOf(2, r.width() / 32)
                val th = maxOf(2, r.height() / 32)
                val crop = android.graphics.Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                val tiny = android.graphics.Bitmap.createScaledBitmap(crop, tw, th, true)
                if (tiny !== crop) crop.recycle()
                val soft = android.graphics.Bitmap.createScaledBitmap(tiny, tw * 4, th * 4, true)
                if (soft !== tiny) tiny.recycle()
                e.view.setBackdrop(soft)
            } catch (_: Throwable) {
            }
        }
    }

    /** Active ou non le style « Verre » (sans effet si le téléphone ne sait pas flouter). */
    fun setGlass(on: Boolean) {
        val want = on && glassSupported

        if (want == glassOn) return
        glassOn = want
        for (e in entries.values) {
            if (!want) removeGlass(e)
            place(e)
        }
    }

    /** Le flou a échoué une fois sur ce téléphone : masques opaques jusqu'au redémarrage. */
    private var glassBroken = false

    private fun placeGlass(e: Entry, shown: Rect) {
        if (!glassOn || glassBroken || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            e.view.alpha = 1f
            return
        }
        try {
            // La fenêtre des masques doit être en place (son jeton sert à rattacher le verre).
            val token = ensureContainer()?.windowToken
            if (token == null) {
                e.view.alpha = 1f
                container?.post { if (entries.containsValue(e)) place(e) }
                return
            }
            val g = (e.glass as? GlassWindow) ?: GlassWindow(context, dark, token).also { e.glass = it }
            g.setDark(dark)
            g.setPending(e.pending)
            if (shown.isEmpty) {
                g.hide()
                return
            }
            if (!g.place(Rect(shown))) throw IllegalStateException("fenêtre floutée refusée")
        } catch (t: Throwable) {
            // Quoi qu'il arrive, le masque opaque reste en place : rien ne s'affiche dessous.
            if (GlassWindow.lastError == null) GlassWindow.lastError = t.toString().take(300)
            glassBroken = true
            entries.values.forEach { removeGlass(it) }
            e.view.alpha = 1f
            return
        }
        // Le masque opaque reste dessous un court instant, le temps que la fenêtre floutée
        // apparaisse (rien n'est visible entre les deux), puis il s'efface.
        if (e.view.alpha > 0f) e.view.postDelayed({ if (e.glass != null) e.view.alpha = 0f }, 120)
    }

    private fun removeGlass(e: Entry) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (e.glass as? GlassWindow)?.dismiss()
        } catch (_: Throwable) {
        }
        e.glass = null
        e.view.alpha = 1f
    }

    /** Le style « Verre » fonctionne-t-il réellement (pour l'afficher dans les Réglages) ? */
    val glassWorking: Boolean get() = glassOn && !glassBroken

    // --- Boucliers (mode « Bloqué ») ----------------------------------------------------------
    // Chaque masque visible reçoit une petite fenêtre invisible qui absorbe les appuis. Pendant
    // un défilement, les boucliers sont rendus inactifs (les masques bougent) puis replacés dès
    // que la page s'arrête.

    /** Appelé avec le tracé d'un glissement fait sur un bouclier, à rejouer sur l'appli. */
    var onShieldSwipe: ((List<TouchPoint>) -> Unit)? = null
    private var shieldsOn = false
    private var shieldsActive = true
    private var shieldsSuspendedUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable { try { syncShields() } catch (_: Throwable) {} }

    fun setShieldsEnabled(on: Boolean) {
        if (on == shieldsOn) return
        shieldsOn = on
        if (!on) entries.values.forEach { removeShield(it) }
        syncShields()
    }

    /** Boucliers traversables pendant [ms] (le temps de rejouer un glissement sur l'appli). */
    fun suspendShields(ms: Long) {
        shieldsSuspendedUntil = SystemClock.uptimeMillis() + ms
        setShieldsTouchable(false)
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, ms)
    }

    /** Un masque a bougé : boucliers inactifs, puis replacés quand tout est immobile. */
    private fun moved() {
        setShieldsTouchable(false)
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    private fun setShieldsTouchable(on: Boolean) {
        if (on == shieldsActive) return
        shieldsActive = on
        for (e in entries.values) {
            e.shieldLp?.let { lp ->
                lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try { wm.updateViewLayout(e.shield, lp) } catch (_: Exception) {}
            }
            e.infoLp?.let { lp ->
                lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try { wm.updateViewLayout(e.info, lp) } catch (_: Exception) {}
            }
        }
    }

    private fun syncShields() {
        // Boutons « i » (tous les modes sauf « Bloqué », où le bouclier s'en charge).
        for (e in entries.values) syncInfo(e, visiblePart(e.rect, e.clip))
        if (!shieldsOn) {
            shieldsActive = true
            return
        }
        if (SystemClock.uptimeMillis() < shieldsSuspendedUntil) {
            handler.postDelayed(settle, shieldsSuspendedUntil - SystemClock.uptimeMillis())
            return
        }
        for (e in entries.values) {
            val shown = visiblePart(e.rect, e.clip)
            // Légèrement en retrait des bords : on ne bloque pas les éléments voisins.
            shown.inset(minOf(6, shown.width() / 4), minOf(6, shown.height() / 4))
            if (e.pending || e.view.visibility != View.VISIBLE || shown.width() < 8 || shown.height() < 8) {
                removeShield(e)
                continue
            }
            val lp = e.shieldLp ?: newShieldLp().also { e.shieldLp = it }
            lp.x = shown.left
            lp.y = shown.top
            lp.width = shown.width()
            lp.height = shown.height()
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                val v = e.shield
                if (v == null) {
                    val sv = ShieldView(context, onTap = { x, y ->
                        // Appui sur le « i » (coin bas droit du masque) : catégorie.
                        val vis = visiblePart(e.rect, e.clip)
                        val zone = (48 * context.resources.displayMetrics.density).toInt()
                        if (e.view.hasInfoBadge() && x >= vis.right - zone && y >= vis.bottom - zone) showInfo(e)
                    }) { pts -> onShieldSwipe?.invoke(pts) }
                    wm.addView(sv, lp)
                    e.shield = sv
                } else {
                    wm.updateViewLayout(v, lp)
                }
            } catch (_: Exception) {
                removeShield(e)
            }
        }
        shieldsActive = true
    }

    private fun newShieldLp() = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "MindFilterShield"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun removeShield(e: Entry) {
        val v = e.shield ?: return
        e.shield = null
        e.shieldLp = null
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val entries = LinkedHashMap<String, Entry>()
    private var dark = false

    private var container: FrameLayout? = null
    /** Position de notre fenêtre sur l'écran (normalement 0,0). */
    private var originX = 0
    private var originY = 0
    private val loc = IntArray(2)

    val count: Int get() = entries.size

    /** Affiche exactement [targets] ; renvoie le nombre de masques nouvellement créés. */
    fun render(targets: List<MaskTarget>, darkStyle: Boolean, snapshotAt: Long = 0L): Int {
        dark = darkStyle
        var created = 0
        val wanted = targets.mapTo(HashSet(targets.size)) { it.key }
        // Masques qui ne correspondent plus à aucune clé : ils peuvent être réutilisés par une
        // publication au même endroit dont la clé a changé (texte qui s'allonge en entrant dans
        // l'écran…). Ainsi le masque reste en place au lieu de disparaître puis réapparaître.
        val spare = entries.keys.filter { it !in wanted }.toMutableList()
        for (t in targets) {
            var e = entries[t.key]
            if (e == null) {
                val reuse = spare.firstOrNull { k -> entries[k]?.let { overlaps(it.rect, t.rect) } == true }
                if (reuse != null) {
                    spare.remove(reuse)
                    e = entries.remove(reuse)!!
                    entries[t.key] = e
                    e.trackedAt = 0L
                } else {
                    if (add(t) && t.kind != MaskTarget.KIND_PENDING) created++
                    continue
                }
            }
            e.view.setDark(darkStyle)
            e.label = t.label
            val nowPending = t.kind == MaskTarget.KIND_PENDING
            // Image d'abord cachée par précaution puis reconnue : c'est un vrai masquage.
            if (e.pending && !nowPending) created++
            val pendingChanged = e.pending != nowPending
            e.pending = nowPending
            if (pendingChanged) place(e)
            e.view.setPending(nowPending)
            if (e.trackedAt > snapshotAt) {
                // Le suivi en direct a une position plus récente que cette analyse : on la garde.
                if (e.clip != t.clip) {
                    e.clip.set(t.clip)
                    place(e)
                }
            } else if (e.clip != t.clip || !nearlySame(e.rect, t.rect)) {
                // Écart de 1 ou 2 px seulement (arrondis, mise en page qui s'ajuste) : le masque
                // ne bouge pas, sinon il tremble à chaque analyse.
                e.rect.set(t.rect)
                e.clip.set(t.clip)
                place(e)
            }
        }
        spare.forEach { remove(it) }
        if (entries.isEmpty()) detach()
        return created
    }

    private val jitterPx = (2 * context.resources.displayMetrics.density).toInt().coerceAtLeast(2)

    /** Même zone à quelques pixels près (aucun bord ne bouge de plus de [jitterPx]). */
    private fun nearlySame(a: Rect, b: Rect): Boolean =
        kotlin.math.abs(a.left - b.left) <= jitterPx && kotlin.math.abs(a.top - b.top) <= jitterPx &&
            kotlin.math.abs(a.right - b.right) <= jitterPx && kotlin.math.abs(a.bottom - b.bottom) <= jitterPx

    /** Deux zones se recouvrent-elles à plus de moitié (par rapport à la plus petite) ? */
    private fun overlaps(a: Rect, b: Rect): Boolean {
        val i = Rect(a)
        if (!i.intersect(b)) return false
        val inter = i.width().toLong() * i.height()
        val smaller = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return smaller > 0 && inter * 2 >= smaller
    }

    /** Décalage immédiat pendant un défilement, avant la prochaine analyse complète. */
    fun offsetAll(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val recent = android.os.SystemClock.uptimeMillis() - 150
        for (e in entries.values) {
            // Masques suivis en direct : leur position est déjà exacte, on n'y touche pas.
            if (e.trackedAt > recent) continue
            com.mindfilter.app.service.ScreenScanner.shiftMask(e.rect, dx, dy, e.clip)
            place(e)
        }
    }

    /** Positions lues en direct par le suivi ; [lost] = masques qui ne peuvent plus être suivis. */
    fun applyTracked(updates: List<Pair<String, Rect>>, lost: List<String>, at: Long) {
        for ((key, rect) in updates) {
            val e = entries[key] ?: continue
            e.trackedAt = at
            if (e.rect != rect) {
                e.rect.set(rect)
                place(e)
            }
        }
        for (key in lost) entries[key]?.trackedAt = 0L
    }

    /** Conservé pour compatibilité : les masques laissent toujours passer les gestes. */
    @Suppress("UNUSED_PARAMETER")
    fun setTouchable(value: Boolean) {}

    fun clear() {
        entries.keys.toList().forEach { remove(it) }
        detach()
    }

    private fun ensureContainer(): FrameLayout? {
        container?.let { return it }
        val c = FrameLayout(context).apply {
            clipChildren = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // Si la fenêtre n'est pas exactement en (0,0), on corrige toutes les positions.
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.getLocationOnScreen(loc)
                if (loc[0] != originX || loc[1] != originY) {
                    originX = loc[0]
                    originY = loc[1]
                    entries.values.forEach { place(it) }
                }
            }
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = "MindFilterMasks"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            wm.addView(c, p)
            originX = 0
            originY = 0
            container = c
            c
        } catch (e: Exception) {
            null
        }
    }

    private fun detach() {
        val c = container ?: return
        container = null
        try {
            wm.removeViewImmediate(c)
        } catch (_: Exception) {
        }
    }

    private fun add(t: MaskTarget): Boolean {
        val c = ensureContainer() ?: return false
        val view = MaskView(context, dark, relay)
        view.setHue(hue)
        view.setFakeGlass(fakeGlass)
        view.setPending(t.kind == MaskTarget.KIND_PENDING)
        val e = Entry(view, Rect(t.rect), Rect(t.clip))
        e.label = t.label
        val shown = visiblePart(e.rect, e.clip)
        val lp = FrameLayout.LayoutParams(shown.width().coerceAtLeast(1), shown.height().coerceAtLeast(1))
        lp.gravity = Gravity.TOP or Gravity.START
        return try {
            c.addView(view, lp)
            e.pending = t.kind == MaskTarget.KIND_PENDING
            entries[t.key] = e
            place(e)
            // Apparition très courte : le contenu n'a pas le temps d'être lu.
            // Opaque immédiatement : aucune image où le contenu dessous serait visible.
            view.alpha = 1f
            true
        } catch (ex: Exception) {
            false
        }
    }

    /** Partie de la publication à couvrir : sa zone complète, limitée à la liste visible. */
    companion object {
        /** Délai d'immobilité avant de replacer les boucliers. */
        const val SETTLE_MS = 160L
    }

    private fun visiblePart(full: Rect, clip: Rect): Rect {
        val r = Rect(full)
        return if (r.intersect(clip)) r else Rect(clip.left, clip.top, clip.left, clip.top)
    }

    /**
     * Place le masque : la position change par simple translation (aucun recalcul de
     * fenêtre) ; la taille ne change que lorsque la publication passe sous un bord.
     */
    private fun place(e: Entry) {
        moved()
        val shown = visiblePart(e.rect, e.clip)
        placeGlass(e, shown)
        if (shown.isEmpty) {
            e.view.visibility = View.INVISIBLE
            return
        }
        e.view.visibility = View.VISIBLE
        e.view.translationX = (shown.left - originX).toFloat()
        e.view.translationY = (shown.top - originY).toFloat()
        val lp = e.view.layoutParams ?: return
        if (lp.width != shown.width() || lp.height != shown.height()) {
            lp.width = shown.width()
            lp.height = shown.height()
            e.view.layoutParams = lp
        }
    }

    /** Le point (coordonnées écran) est-il sous un masque visible ? */
    fun hits(x: Int, y: Int): Boolean = entries.values.any { e ->
        e.view.visibility == View.VISIBLE && visiblePart(e.rect, e.clip).contains(x, y)
    }

    /** Masques retirés il y a moins d'une demi-seconde (zone visible + moment). */
    private val recentlyRemoved = ArrayList<Pair<Rect, Long>>()

    /** Comme [hits], en comptant aussi les masques retirés il y a un instant. */
    fun hitsRecent(x: Int, y: Int): Boolean {
        if (hits(x, y)) return true
        val limit = android.os.SystemClock.uptimeMillis() - 500
        recentlyRemoved.removeAll { it.second < limit }
        return recentlyRemoved.any { it.first.contains(x, y) }
    }

    private fun remove(key: String) {
        val e = entries.remove(key) ?: return
        removeShield(e)
        removeInfo(e)
        removeGlass(e)
        if (e.view.visibility == View.VISIBLE && !e.pending) {
            recentlyRemoved.add(visiblePart(e.rect, e.clip) to android.os.SystemClock.uptimeMillis())
            if (recentlyRemoved.size > 30) recentlyRemoved.removeAt(0)
        }
        try {
            e.view.animate().cancel()
            val c = container
            if (e.pending && e.view.visibility == View.VISIBLE && c != null) {
                // Image jugée sans problème : le masque « Analyse… » s'efface en douceur au
                // lieu de disparaître d'un coup (moins de clignotements en défilant).
                val v = e.view
                v.animate().alpha(0f).setDuration(180).withEndAction {
                    try { c.removeView(v) } catch (_: Exception) {}
                }.start()
            } else {
                c?.removeView(e.view)
            }
        } catch (_: Exception) {
        }
    }
}
