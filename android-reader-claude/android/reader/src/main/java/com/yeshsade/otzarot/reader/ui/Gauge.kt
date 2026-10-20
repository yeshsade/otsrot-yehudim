// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View

/**
 * The progress gauge (sketch approved 2026-10-15, "מה שעשית, מדהים"): built like a car's gauge, filling from left
 * to right. At low percentages it glows pink, orange and purple; the further along, the calmer the purple.
 * No animations (owner) — the arc is drawn where it is. [big] is the day's gauge; the small one is the week's.
 */
class Gauge(context: Context, private val ui: Ui, private val big: Boolean) : View(context) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var caption = ""
        set(v) { field = v; invalidate() }
    var title = ""
        set(v) { field = v; invalidate() }
    /** A tiny gauge (in the drawer): only the arc, no words. */
    var mini = false
        set(v) { field = v; requestLayout(); invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()

    init {
        // the glow is a blur, which the graphics chip does not draw
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = ""
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (mini) { setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec)); return }
        val size = if (big) minOf(w, ui.dp(260)) else minOf(w, ui.dp(150))
        setMeasuredDimension(w, (size * 0.9f).toInt() + ui.dp(4))
    }

    override fun onDraw(canvas: Canvas) {
        val size = if (mini) minOf(width, ((height - ui.dp(4)) / 0.9f).toInt()) else if (big) minOf(width, ui.dp(260)) else minOf(width, ui.dp(150))
        val stroke = size * (if (big) 0.085f else 0.1f)
        val cx = width / 2f
        val r = size / 2f - stroke
        box.set(cx - r, stroke + ui.dp(4), cx + r, stroke + ui.dp(4) + 2 * r)
        track.strokeWidth = stroke
        track.color = ui.colors.selection
        // 240° from bottom-left, over the top, to bottom-right: filling from left to right
        canvas.drawArc(box, START, SWEEP, false, track)
        if (fraction > 0f) {
            fill.strokeWidth = stroke
            val colors = if (fraction < CALM_FROM) intArrayOf(PINK, ORANGE, PURPLE, PINK) else intArrayOf(CALM, CALM, CALM, CALM)
            val shader = SweepGradient(cx, box.centerY(), colors, floatArrayOf(0f, 0.33f, 0.66f, 1f))
            shader.setLocalMatrix(Matrix().apply { setRotate(START, cx, box.centerY()) })
            fill.shader = shader
            if (fraction < CALM_FROM) {
                glow.strokeWidth = stroke * 1.6f
                glow.shader = shader
                glow.alpha = 120
                glow.maskFilter = BlurMaskFilter(stroke, BlurMaskFilter.Blur.NORMAL)
                canvas.drawArc(box, START, SWEEP * fraction, false, glow)
            }
            canvas.drawArc(box, START, SWEEP * fraction, false, fill)
        }
        if (mini) return
        text.typeface = ui.heading
        text.color = ui.colors.ink
        text.textSize = size * (if (big) 0.2f else 0.22f)
        canvas.drawText("${(fraction * 100).toInt()}%", cx, box.centerY() + text.textSize * 0.3f, text)
        text.typeface = ui.font
        text.color = ui.colors.muted
        text.textSize = size * (if (big) 0.068f else 0.09f)
        if (title.isNotEmpty()) canvas.drawText(title, cx, box.centerY() - size * (if (big) 0.17f else 0.2f), text)
        if (caption.isNotEmpty()) canvas.drawText(caption, cx, box.centerY() + size * (if (big) 0.2f else 0.24f), text)
    }

    companion object {
        const val START = 150f
        const val SWEEP = 240f
        const val CALM_FROM = 0.6f
        val PINK = Color.parseColor("#FF5C8A")
        val ORANGE = Color.parseColor("#FFA048")
        val PURPLE = Color.parseColor("#8E6CFF")
        val CALM = Color.parseColor("#6A55C9")
    }
}
