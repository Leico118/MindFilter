package com.mindfilter.app.overlay

import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * Masque « Verre » : une petite fenêtre qui FLOUTE fortement ce qui se trouve derrière elle
 * (fonction officielle d'Android 12 et plus, « flou d'arrière-plan des fenêtres »), avec un
 * léger voile coloré par-dessus. On devine les couleurs, mais rien n'est lisible.
 *
 * Android ne permet pas de flouter une zone de l'écran d'une autre appli depuis une seule
 * grande fenêtre : chaque masque « Verre » a donc sa propre fenêtre, déplacée avec la page.
 * Elle laisse toujours passer les gestes (les boucliers du mode « Bloqué » s'en chargent).
 */
@RequiresApi(Build.VERSION_CODES.S)
class GlassWindow(context: Context, dark: Boolean, parentToken: android.os.IBinder) {
    private val dp = context.resources.displayMetrics.density
    private val view = MaskView(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault), dark, NO_RELAY).apply {
        setGlass(true)
    }
    private val dialog = Dialog(ContextThemeWrapper(context, android.R.style.Theme_Translucent_NoTitleBar))
    private val lp: WindowManager.LayoutParams
    private var shown = false

    init {
        val w = dialog.window!!
        // Fenêtre « fille » de la fenêtre des masques de MindFilter (qui, elle, a le droit de
        // s'afficher par-dessus les autres applis) : sans ce rattachement, Android refuse la
        // fenêtre floutée et les masques restaient opaques.
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL)
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Le fond de la fenêtre donne la forme (coins arrondis) de la zone floutée.
        w.setBackgroundDrawable(
            GradientDrawable().apply {
                cornerRadius = 28f * dp
                setColor(0x01000000)
            },
        )
        w.setBackgroundBlurRadius((48f * dp).toInt())
        dialog.setContentView(view)
        dialog.setCancelable(false)
        lp = w.attributes
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        lp.windowAnimations = 0
        lp.title = "MindFilterGlass"
        lp.token = parentToken
    }

    fun setDark(value: Boolean) = view.setDark(value)

    fun setPending(value: Boolean) = view.setPending(value)

    fun showInfo(text: String) = view.showInfo(text)

    /** Place la fenêtre sur la zone visible du masque (coordonnées écran). false = refusée. */
    fun place(r: Rect): Boolean {
        if (lp.x == r.left && lp.y == r.top && lp.width == r.width() && lp.height == r.height() && shown) return true
        lp.x = r.left
        lp.y = r.top
        lp.width = r.width().coerceAtLeast(1)
        lp.height = r.height().coerceAtLeast(1)
        return try {
            dialog.window?.attributes = lp
            if (!shown) {
                dialog.show()
                shown = true
            }
            true
        } catch (t: Throwable) {
            lastError = t.toString().take(300)
            false
        }
    }

    fun hide() {
        if (!shown) return
        shown = false
        try { dialog.hide() } catch (_: Exception) {}
    }

    fun dismiss() {
        shown = false
        try { dialog.dismiss() } catch (_: Exception) {}
    }

    companion object {
        private val NO_RELAY = object : DragRelay {
            override fun onDragStart(first: TouchPoint) {}
            override fun onDragMove(p: TouchPoint) {}
            override fun onDragEnd(last: TouchPoint) {}
        }

        /** Dernière raison d'échec du flou (affichée dans les Réglages pour comprendre). */
        @Volatile var lastError: String? = null

        /** Le téléphone sait-il flouter derrière une fenêtre (désactivé en économie d'énergie…) ? */
        fun supported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return try { wm.isCrossWindowBlurEnabled } catch (_: Exception) { false }
        }
    }
}
