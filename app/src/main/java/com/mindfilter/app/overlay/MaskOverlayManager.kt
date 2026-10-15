package com.mindfilter.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.mindfilter.app.service.MaskTarget

/**
 * Gère une fenêtre de superposition d'accessibilité par masque.
 * TYPE_ACCESSIBILITY_OVERLAY ne nécessite pas l'autorisation « afficher par-dessus ».
 * Toutes les méthodes doivent être appelées sur le thread principal.
 */
class MaskOverlayManager(
    private val context: Context,
    private val relay: DragRelay,
) {
    private class Entry(val view: MaskView, val params: WindowManager.LayoutParams, val rect: Rect)

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val entries = LinkedHashMap<String, Entry>()
    private var touchable = true
    private var dark = false

    val count: Int get() = entries.size

    /** Affiche exactement [targets] ; renvoie le nombre de masques nouvellement créés. */
    fun render(targets: List<MaskTarget>, darkStyle: Boolean, touchableMasks: Boolean): Int {
        if (touchableMasks != touchable) setTouchable(touchableMasks)
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
                if (e.rect != t.rect) move(e, t.rect)
            }
        }
        val stale = entries.keys.filter { it !in wanted }
        stale.forEach { remove(it) }
        return created
    }

    /** Décalage immédiat pendant un défilement, avant la prochaine analyse complète. */
    fun offsetAll(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        for (e in entries.values) {
            val r = Rect(e.rect)
            r.offset(dx, dy)
            move(e, r)
        }
    }

    fun setTouchable(value: Boolean) {
        touchable = value
        for (e in entries.values) {
            e.params.flags = flags(value)
            safeUpdate(e)
        }
    }

    fun clear() {
        entries.keys.toList().forEach { remove(it) }
    }

    private fun flags(touchable: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    private fun add(t: MaskTarget): Boolean {
        val view = MaskView(context, dark, relay)
        val p = WindowManager.LayoutParams(
            t.rect.width(),
            t.rect.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags(touchable),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = t.rect.left
            y = t.rect.top
            title = "MindFilterMask"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            view.alpha = 0f
            view.scaleX = 0.97f
            view.scaleY = 0.97f
            wm.addView(view, p)
            view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200).setInterpolator(DecelerateInterpolator()).start()
            entries[t.key] = Entry(view, p, Rect(t.rect))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun move(e: Entry, r: Rect) {
        e.rect.set(r)
        e.params.x = r.left
        e.params.y = r.top
        e.params.width = r.width()
        e.params.height = r.height()
        safeUpdate(e)
    }

    private fun safeUpdate(e: Entry) {
        try {
            wm.updateViewLayout(e.view, e.params)
        } catch (_: Exception) {
        }
    }

    private fun remove(key: String) {
        val e = entries.remove(key) ?: return
        try {
            e.view.animate().cancel()
            wm.removeViewImmediate(e.view)
        } catch (_: Exception) {
        }
    }
}
