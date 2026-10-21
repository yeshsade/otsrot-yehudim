// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * The progress gauge. Built like a car's gauge, filling from left to right (sketch approved 2026-10-15).
 * Its look is the "לוח מחוונים" the owner chose on 2026-10-20: ticks around it (the ones already passed turn
 * orange), an arc whose colour runs from pink through orange to purple as it advances, a glow that sits right
 * on the arc, and a white point of light at its tip. Only the percentage is written — no "3 of 120" (owner,
 * 2026-10-20/21). No animation. [big] is the day's gauge; the small one is the week's; [mini] is the tiny one
 * in the drawer and the toolbar.
 */
class Gauge(context: Context, private val ui: Ui, private val big: Boolean) : View(context) {
    var fraction = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
    var title = ""
        set(v) { field = v; invalidate() }

    /** A tiny gauge (drawer, toolbar): only the arc. */
    var mini = false
        set(v) {
            field = v
            setLayerType(if (v) LAYER_TYPE_NONE else LAYER_TYPE_SOFTWARE, null)
            requestLayout(); invalidate()
        }

    /** In a tiny gauge, the percentage as a number in its middle (the toolbar's "live" gauge). */
    var miniNumber = false
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()

    init {
        // the glow is a blur, which the graphics chip does not draw
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun size(w: Int) = if (big) minOf(w, ui.dp(260)) else minOf(w, ui.dp(160))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (mini) { setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec)); return }
        setMeasuredDimension(w, (size(w) * 0.86f).toInt() + ui.dp(6))
    }

    override fun onDraw(canvas: Canvas) {
        contentDescription = "${(fraction * 100).toInt()}%"
        if (mini) { drawMini(canvas); return }
        val s = size(width).toFloat()
        val cx = width / 2f
        val cy = ui.dp(4) + s / 2f
        val stroke = s * (if (big) 0.075f else 0.09f)
        val tickOuter = s / 2f - ui.dp(2)
        val tickShort = s * (if (big) 0.035f else 0.045f)
        val tickLong = tickShort * 1.8f
        val r = tickOuter - tickLong - s * 0.03f - stroke / 2f
        box.set(cx - r, cy - r, cx + r, cy + r)

        // ticks: every sixth longer; the ones already passed turn orange
        for (i in 0..TICKS) {
            val long = i % 6 == 0
            val a = Math.toRadians((START + SWEEP * i / TICKS).toDouble())
            val inner = tickOuter - (if (long) tickLong else tickShort)
            tick.strokeWidth = if (long) ui.dp(2.5f).toFloat() else ui.dp(1.6f).toFloat()
            tick.color = if (fraction > 0f && i.toFloat() / TICKS <= fraction + 1e-4f) ORANGE else ui.colors.border
            canvas.drawLine(cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(),
                cx + tickOuter * cos(a).toFloat(), cy + tickOuter * sin(a).toFloat(), tick)
        }

        track.strokeWidth = stroke
        track.color = ui.colors.selection
        canvas.drawArc(box, START, SWEEP, false, track)
        if (fraction > 0f) {
            val shader = sweep(cx, cy)
            // the glow: the same arc, the same width, blurred — so it hugs the arc instead of a shadow beside it
            glow.strokeWidth = stroke
            glow.shader = shader
            glow.alpha = 150
            glow.maskFilter = BlurMaskFilter(stroke * 0.7f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawArc(box, START, SWEEP * fraction, false, glow)
            fill.strokeWidth = stroke
            fill.shader = shader
            canvas.drawArc(box, START, SWEEP * fraction, false, fill)
            // the point of light at the tip
            val a = Math.toRadians((START + SWEEP * fraction).toDouble())
            val tx = cx + r * cos(a).toFloat()
            val ty = cy + r * sin(a).toFloat()
            dot.shader = RadialGradient(tx, ty, stroke * 0.95f, intArrayOf(Color.WHITE, 0xFFFFD6A6.toInt(), 0x00FF9A3D),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(tx, ty, stroke * 0.95f, dot)
            dot.shader = null
            dot.color = Color.WHITE
            canvas.drawCircle(tx, ty, stroke * 0.27f, dot)
        }

        text.typeface = ui.heading
        text.color = ui.colors.ink
        text.textSize = s * (if (big) 0.21f else 0.23f)
        canvas.drawText("${(fraction * 100).toInt()}%", cx, cy + text.textSize * 0.35f, text)
        if (title.isNotEmpty()) {
            text.typeface = ui.font
            text.color = ui.colors.muted
            text.textSize = s * (if (big) 0.068f else 0.085f)
            canvas.drawText(title, cx, cy - s * (if (big) 0.15f else 0.17f), text)
        }
    }

    /**
     * Pink at the start, orange half way, purple at the end — fixed along the arc. The gradient starts a little
     * before the arc, so the arc's rounded start stays pink (a gradient that begins exactly there wraps round to
     * its last colour, and the start showed a purple blob).
     */
    private fun sweep(cx: Float, cy: Float): SweepGradient {
        val lead = 20f
        val shader = SweepGradient(cx, cy, intArrayOf(PINK, PINK, ORANGE, PURPLE, PURPLE, PINK),
            floatArrayOf(0f, lead / 360f, (lead + SWEEP / 2f) / 360f, (lead + SWEEP) / 360f, (lead + SWEEP + 20f) / 360f, 1f))
        shader.setLocalMatrix(Matrix().apply { setRotate(START - lead, cx, cy) })
        return shader
    }

    private fun drawMini(canvas: Canvas) {
        val s = minOf(width.toFloat(), height / 0.86f)
        val stroke = s * 0.14f
        val cx = width / 2f
        val r = s / 2f - stroke / 2f - 1f
        val cy = (height - (r + r * 0.5f)) / 2f + r
        box.set(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = stroke
        track.color = ui.colors.selection
        canvas.drawArc(box, START, SWEEP, false, track)
        if (fraction > 0f) {
            fill.strokeWidth = stroke
            fill.shader = sweep(cx, cy)
            canvas.drawArc(box, START, SWEEP * fraction, false, fill)
        }
        if (miniNumber) {
            text.typeface = ui.heading
            text.color = ui.colors.ink
            text.textSize = s * 0.32f
            canvas.drawText("${(fraction * 100).toInt()}", cx, cy + text.textSize * 0.36f, text)
        }
    }

    companion object {
        const val START = 150f
        const val SWEEP = 240f
        const val TICKS = 24
        const val CALM_FROM = 0.6f
        val PINK = Color.parseColor("#FF4F86")
        val ORANGE = Color.parseColor("#FF9A3D")
        val PURPLE = Color.parseColor("#8E6CFF")
        val CALM = Color.parseColor("#6A55C9")
    }
}
