package com.mindfilter.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Bouclier invisible posé sur un masque (mode « Bloqué ») : il absorbe les appuis, qui
 * n'atteignent donc jamais la publication dessous. Un glissement est enregistré puis rejoué
 * sur l'appli dès que le doigt se lève (Android interdit de le rejouer pendant que le doigt
 * touche l'écran) : la page défile quand même.
 */
@SuppressLint("ViewConstructor")
class ShieldView(context: Context, private val onSwipe: (List<TouchPoint>) -> Unit) : View(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val points = ArrayList<TouchPoint>(64)
    private var moved = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                moved = false
                points.add(TouchPoint(event.rawX, event.rawY, event.eventTime))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    val dx = event.getHistoricalX(i) - event.x
                    val dy = event.getHistoricalY(i) - event.y
                    points.add(TouchPoint(event.rawX + dx, event.rawY + dy, event.getHistoricalEventTime(i)))
                }
                points.add(TouchPoint(event.rawX, event.rawY, event.eventTime))
                val first = points.first()
                if (!moved && hypot(event.rawX - first.x, event.rawY - first.y) > slop) moved = true
            }
            MotionEvent.ACTION_UP -> {
                points.add(TouchPoint(event.rawX, event.rawY, event.eventTime))
                // Appui simple, double ou long : absorbé, rien n'est transmis.
                if (moved && points.size >= 2) onSwipe(points.toList())
                points.clear()
            }
            MotionEvent.ACTION_CANCEL -> points.clear()
        }
        return true
    }
}
