// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.yeshsade.otzarot.reader.data.ScrubberPolicy

/**
 * The page handle (owner's decisions 2026-10-14): a thumb at the edge of the screen that jumps through the
 * document. It moves only after being held for a while (0.5 s by default) so a swipe near the edge does
 * not jump by mistake. While dragging, a bubble says "page X of Y". In side-to-side reading it runs along
 * the bottom instead.
 *
 * The view covers the reading area but only takes touches that start on the thumb; all others go to the pages.
 */
@SuppressLint("ViewConstructor")
class Scrubber(
    context: Context,
    private val ui: Ui,
    /** "right" or "left" — the physical side, the same in Hebrew and English. */
    private val side: String,
    private val horizontal: Boolean,
    private val holdMillis: Int,
    /** Space kept free at the top (the top bar) and at the bottom (the toolbar). */
    private val topInset: () -> Int,
    private val bottomInset: () -> Int,
    private val listener: Listener,
) : View(context) {
    interface Listener {
        /** Moves the document to [fraction] (0..1) and returns the text for the bubble. */
        fun scrubTo(fraction: Float): String
        fun scrubStarted()
        fun scrubEnded()
    }

    /** Where the document is, 0..1. While the thumb is held it follows the finger only. */
    var fraction = 0f
        private set

    /** The document moved (by reading, or by the engine after a drag): the thumb follows, unless it is held. */
    fun follow(v: Float) {
        if (pressed) return
        fraction = v.coerceIn(0f, 1f)
        invalidate()
    }

    /**
     * Whether the frame is up. While it is down the handle still shows itself whenever the pages move
     * ("it should always appear while flipping"), and fades away a moment after they stop.
     */
    var frameShown: () -> Boolean = { true }

    private val peekOut = Runnable { if (!frameShown() && !pressed) animate().alpha(0f).setDuration(PEEK_FADE_MS).withEndAction { if (!frameShown() && !pressed) visibility = INVISIBLE }.start() }

    /** The pages moved: show the handle for a moment even while the frame is hidden, so it can be grabbed. */
    fun pagesMoved() {
        if (frameShown()) return
        removeCallbacks(peekOut)
        if (visibility != VISIBLE || alpha < 1f) {
            animate().cancel()
            visibility = VISIBLE
            animate().alpha(1f).setDuration(PEEK_FADE_MS).start()
        }
        if (!pressed) postDelayed(peekOut, PEEK_STAY_MS)
    }

    private val thumbLong = ui.dp(56).toFloat()
    private val thumbThick = ui.dp(10).toFloat()
    private val edge = ui.dp(6).toFloat()
    private val margin = ui.dp(16).toFloat()
    private val touchSlack = ui.dp(22).toFloat()

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.colors.accent }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); strokeWidth = ui.dp(1.5f).toFloat() }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.colors.header }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = ui.dp(16).toFloat(); typeface = ui.medium; textAlign = Paint.Align.CENTER
    }

    private var pressed = false
    private var dragging = false
    private var bubble = ""
    private var grabOffset = 0f
    private val startDrag = Runnable { beginDrag() }
    /**
     * The thumb is drawn under the finger at once; the document follows once per frame. Moving a big
     * booklet takes the main thread a while, so moving it on every finger event made the thumb lag behind.
     */
    private var moveQueued = false
    private val moveDocument = Runnable {
        moveQueued = false
        if (!dragging) return@Runnable
        bubble = listener.scrubTo(fraction)
        invalidate()
    }

    init { tag = "scrubber" }

    // ---------- geometry ----------

    private val trackStart get() = if (horizontal) margin else topInset() + margin
    private val trackEnd get() = if (horizontal) width - margin else height - bottomInset() - margin
    private val trackLength get() = (trackEnd - trackStart - thumbLong).coerceAtLeast(1f)
    private val crossPos get() = if (horizontal) height - bottomInset() - edge - thumbThick
        else if (side == "left") edge else width - edge - thumbThick

    private fun thumbRect(): RectF {
        val along = trackStart + fraction * trackLength
        return if (horizontal) RectF(along, crossPos, along + thumbLong, crossPos + thumbThick)
        else RectF(crossPos, along, crossPos + thumbThick, along + thumbLong)
    }

    private fun fractionAt(x: Float, y: Float): Float =
        (((if (horizontal) x else y) - grabOffset - trackStart) / trackLength).coerceIn(0f, 1f)

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        val r = thumbRect()
        if (dragging) r.inset(-ui.dp(3).toFloat(), -ui.dp(3).toFloat())
        val radius = thumbThick
        canvas.drawRoundRect(r, radius, radius, thumbPaint)
        // three short grip lines
        val cx = r.centerX(); val cy = r.centerY(); val g = ui.dp(5).toFloat(); val l = thumbThick * .3f
        for (i in -1..1) {
            if (horizontal) canvas.drawLine(cx + i * g, cy - l, cx + i * g, cy + l, gripPaint)
            else canvas.drawLine(cx - l, cy + i * g, cx + l, cy + i * g, gripPaint)
        }
        if (dragging && bubble.isNotEmpty()) drawBubble(canvas, r)
    }

    private fun drawBubble(canvas: Canvas, thumb: RectF) {
        val padH = ui.dp(16).toFloat(); val h = ui.dp(44).toFloat()
        val w = textPaint.measureText(bubble) + padH * 2
        val gap = ui.dp(14).toFloat()
        val box = if (horizontal) {
            val left = (thumb.centerX() - w / 2).coerceIn(margin, width - margin - w)
            RectF(left, thumb.top - gap - h, left + w, thumb.top - gap)
        } else {
            val top = (thumb.centerY() - h / 2).coerceIn(trackStart, (trackEnd - h).coerceAtLeast(trackStart))
            val left = if (side == "left") thumb.right + gap else thumb.left - gap - w
            RectF(left, top, left + w, top + h)
        }
        canvas.drawRoundRect(box, h / 2, h / 2, bubblePaint)
        val baseline = box.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(bubble, box.centerX(), baseline, textPaint)
    }

    // ---------- touching ----------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (visibility != VISIBLE) return false
                val hit = RectF(thumbRect()).apply { inset(-touchSlack, -touchSlack) }
                if (!hit.contains(event.x, event.y)) return false
                pressed = true
                val r = thumbRect()
                grabOffset = if (horizontal) event.x - r.left else event.y - r.top
                listener.scrubStarted()
                if (holdMillis <= 0) beginDrag() else postDelayed(startDrag, holdMillis.toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressed) return false
                if (dragging) {
                    fraction = fractionAt(event.x, event.y)
                    invalidate()
                    if (!moveQueued) { moveQueued = true; postOnAnimation(moveDocument) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startDrag)
                if (dragging) { removeCallbacks(moveDocument); moveQueued = false; listener.scrubTo(fraction) }
                if (pressed) listener.scrubEnded()
                pressed = false
                dragging = false
                bubble = ""
                invalidate()
                if (!frameShown()) postDelayed(peekOut, PEEK_STAY_MS)
                return true
            }
        }
        return pressed
    }

    private fun beginDrag() {
        if (!pressed) return
        dragging = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        bubble = listener.scrubTo(fraction)
        invalidate()
    }

    private companion object {
        const val PEEK_STAY_MS = 1500L
        const val PEEK_FADE_MS = 200L
    }

    /** For tests: the centre of the thumb, in this view's coordinates. */
    fun thumbCentre(): FloatArray = thumbRect().let { floatArrayOf(it.centerX(), it.centerY()) }

    /** Page for a fraction, for the bubble when the engine has not moved yet. */
    fun pageAt(fraction: Float, pages: Int) = ScrubberPolicy.pageAt(fraction, pages)
}
