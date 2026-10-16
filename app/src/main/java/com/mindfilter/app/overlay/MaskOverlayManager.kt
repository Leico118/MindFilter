package com.mindfilter.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
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
        val wanted = HashSet<String>(targets.size)
        for (t in targets) {
            wanted.add(t.key)
            val e = entries[t.key]
            if (e == null) {
                if (add(t)) created++
            } else {
                e.view.setDark(darkStyle)
                if (e.trackedAt > snapshotAt) {
                    // Le suivi en direct a une position plus récente que cette analyse : on la garde.
                    if (e.clip != t.clip) {
                        e.clip.set(t.clip)
                        place(e)
                    }
                } else if (e.rect != t.rect || e.clip != t.clip) {
                    e.rect.set(t.rect)
                    e.clip.set(t.clip)
                    place(e)
                }
            }
        }
        val stale = entries.keys.filter { it !in wanted }
        stale.forEach { remove(it) }
        if (entries.isEmpty()) detach()
        return created
    }

    /** Décalage immédiat pendant un défilement, avant la prochaine analyse complète. */
    fun offsetAll(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val recent = android.os.SystemClock.uptimeMillis() - 150
        for (e in entries.values) {
            // Masques suivis en direct : leur position est déjà exacte, on n'y touche pas.
            if (e.trackedAt > recent) continue
            e.rect.offset(dx, dy)
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
        val e = Entry(view, Rect(t.rect), Rect(t.clip))
        val shown = visiblePart(e.rect, e.clip)
        val lp = FrameLayout.LayoutParams(shown.width().coerceAtLeast(1), shown.height().coerceAtLeast(1))
        lp.gravity = Gravity.TOP or Gravity.START
        return try {
            c.addView(view, lp)
            entries[t.key] = e
            place(e)
            // Apparition très courte : le contenu n'a pas le temps d'être lu.
            view.alpha = 0.55f
            view.animate().alpha(1f).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
            true
        } catch (ex: Exception) {
            false
        }
    }

    /** Partie de la publication à couvrir : sa zone complète, limitée à la liste visible. */
    private fun visiblePart(full: Rect, clip: Rect): Rect {
        val r = Rect(full)
        return if (r.intersect(clip)) r else Rect(clip.left, clip.top, clip.left, clip.top)
    }

    /**
     * Place le masque : la position change par simple translation (aucun recalcul de
     * fenêtre) ; la taille ne change que lorsque la publication passe sous un bord.
     */
    private fun place(e: Entry) {
        val shown = visiblePart(e.rect, e.clip)
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

    private fun remove(key: String) {
        val e = entries.remove(key) ?: return
        try {
            e.view.animate().cancel()
            container?.removeView(e.view)
        } catch (_: Exception) {
        }
    }
}
