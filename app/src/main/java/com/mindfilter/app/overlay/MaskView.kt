package com.mindfilter.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.min

/** Point d'un geste, en coordonnées écran. */
data class TouchPoint(val x: Float, val y: Float, val t: Long)

/**
 * Plaque de « verre dépoli » opaque (le contenu dessous reste illisible) avec une
 * capsule noire « Masqué » au centre. Aucun geste ne permet de révéler le contenu.
 */
@SuppressLint("ViewConstructor")
class MaskView(
    context: Context,
    private var dark: Boolean,
    private val onDrag: (List<TouchPoint>) -> Unit,
) : View(context) {

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val capsule = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.01f
    }
    private val rect = RectF()
    private val pill = RectF()
    private val eye = Path()

    private val points = ArrayList<TouchPoint>(64)
    private var dragging = false

    init {
        isClickable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setDark(value: Boolean) {
        if (value != dark) {
            dark = value
            onSizeChanged(width, height, width, height)
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        fill.color = if (dark) 0xFF24262D.toInt() else 0xFFE4E7EE.toInt()
        sheen.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            if (dark) 0x26FFFFFF else 0x8CFFFFFF.toInt(),
            if (dark) 0x05FFFFFF else 0x14FFFFFF,
            Shader.TileMode.CLAMP,
        )
        glow.shader = RadialGradient(
            w * 0.15f, h * 0.05f, maxOf(w, h) * 0.75f,
            if (dark) 0x1FB4C8FF else 0x66FFFFFF,
            0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
        border.strokeWidth = 1f * dp
        border.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            if (dark) 0x40FFFFFF else 0xE6FFFFFF.toInt(),
            if (dark) 0x0FFFFFFF else 0x59FFFFFF,
            Shader.TileMode.CLAMP,
        )
        capsule.color = 0xEB0B0B0D.toInt()
        textPaint.textSize = 13f * sp
        iconPaint.strokeWidth = 1.6f * dp
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val inset = border.strokeWidth / 2f
        rect.set(inset, inset, w - inset, h - inset)
        val radius = min(28f * dp, min(w, h) / 2f)

        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, glow)
        canvas.drawRoundRect(rect, radius, radius, sheen)
        canvas.drawRoundRect(rect, radius, radius, border)

        // Capsule centrale
        val label = "Masqué"
        val iconW = 18f * dp
        val gap = 6f * dp
        val padH = 13f * dp
        val textW = textPaint.measureText(label)
        val fullW = padH + iconW + gap + textW + padH
        val capH = min(32f * dp, h - 8f * dp)
        if (capH < 18f * dp) return
        val showText = fullW + 16f * dp <= w
        val capW = if (showText) fullW else capH + 8f * dp
        val cx = w / 2f
        val cy = h / 2f
        pill.set(cx - capW / 2f, cy - capH / 2f, cx + capW / 2f, cy + capH / 2f)
        canvas.drawRoundRect(pill, capH / 2f, capH / 2f, capsule)

        val iconCx = if (showText) pill.left + padH + iconW / 2f else cx
        drawEye(canvas, iconCx, cy, iconW)
        if (showText) {
            val fm = textPaint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, pill.left + padH + iconW + gap, baseline, textPaint)
        }
    }

    /** Œil barré, tracé à la main pour rester net à toutes les tailles. */
    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val hw = size / 2f
        val hh = size * 0.32f
        eye.reset()
        eye.moveTo(cx - hw, cy)
        eye.quadTo(cx, cy - hh * 2f, cx + hw, cy)
        eye.quadTo(cx, cy + hh * 2f, cx - hw, cy)
        eye.close()
        canvas.drawPath(eye, iconPaint)
        canvas.drawCircle(cx, cy, size * 0.14f, iconPaint)
        canvas.drawLine(cx - hw * 0.8f, cy - hw * 0.8f, cx + hw * 0.8f, cy + hw * 0.8f, iconPaint)
    }

    /**
     * Tous les appuis sont absorbés (simple, double, long) : rien ne passe à la
     * publication située dessous. Un glissement est transmis au service, qui le rejoue
     * sur l'application pour faire défiler la page.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                dragging = false
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
                if (!dragging && hypot(event.rawX - first.x, event.rawY - first.y) > touchSlop) dragging = true
            }
            MotionEvent.ACTION_UP -> {
                points.add(TouchPoint(event.rawX, event.rawY, event.eventTime))
                if (dragging && points.size >= 2) onDrag(ArrayList(points))
                points.clear()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                points.clear()
                dragging = false
            }
        }
        return true
    }
}
