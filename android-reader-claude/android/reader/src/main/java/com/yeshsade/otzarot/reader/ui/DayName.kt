// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.animation.LinearInterpolator
import com.github.barteksc.pdfviewer.PDFView
import com.yeshsade.otzarot.reader.data.ProgressMap

/**
 * The day's name, faint, in the middle of the page (owner, 2026-10-17): "במרכז, בשקיפות כמעט מרבית, שם היום".
 * It shows for a few seconds after each touch of the screen, then fades. A page split between two days (Tehillim)
 * shows each day's name on its own part ("חצי חצי": the owner approved the sketch); that is how the booklet is,
 * not a setting. Drawn with the page, in the same frame — nothing extra is laid over the reader.
 */
class DayName(private val pdfView: PDFView, typeface: Typeface, private val night: Boolean) {
    var map: ProgressMap? = null
        set(value) { field = value; pdfView.invalidate() }

    /** Whether it may show now (the setting, and whether the frame is shown). */
    var allowed: () -> Boolean = { true }

    private var strength = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textAlign = Paint.Align.CENTER
        color = if (night) 0xFFFFFFFF.toInt() else 0xFF2A2340.toInt()
    }

    /** A touch: show the names, then fade them out. */
    fun touched() {
        if (map == null || !allowed()) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = SHOW_MS + FADE_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val elapsed = it.currentPlayTime
                strength = if (elapsed < SHOW_MS) 1f else (1f - (elapsed - SHOW_MS).toFloat() / FADE_MS).coerceIn(0f, 1f)
                pdfView.invalidate()
            }
            start()
        }
    }

    fun hide() { animator?.cancel(); strength = 0f; pdfView.invalidate() }

    fun draw(canvas: Canvas) {
        val map = map ?: return
        if (strength <= 0f) return
        val range = pdfView.visiblePageRange ?: return
        for (page in range[0]..range[1]) {
            val info = map.pageInfo(page) ?: continue
            if (info.weekOffset != 0) continue
            for ((dayId, frame) in parts(info, page)) {
                val label = map.day(dayId)?.label ?: continue
                val box = pdfView.frameRectToView(page, frame)
                if (android.util.Log.isLoggable("DayName", android.util.Log.DEBUG)) android.util.Log.d("DayName", "page $page $dayId $frame -> $box open=${pdfView.isPageOpen(page)}")
                box ?: continue
                drawName(canvas, label, box)
            }
        }
    }

    /** Each day on this page and the part of the page (in points of the page as seen) that is that day's. */
    private fun parts(info: ProgressMap.PageInfo, page: Int): List<Pair<String, RectF>> {
        val byDay = info.regions.filter { it.dayId != null }.groupBy { it.dayId!! }
        if (byDay.size > 1) {
            return byDay.map { (day, regions) ->
                day to RectF(regions.minOf { it.left }, regions.minOf { it.top }, regions.maxOf { it.right }, regions.maxOf { it.bottom })
            }
        }
        val day = info.dayId ?: byDay.keys.firstOrNull() ?: return emptyList()
        // the whole page; its size is read only when the engine already has the page (never waits on the engine here)
        if (!pdfView.isPageOpen(page)) return emptyList()
        val size = pdfView.getFrameSize(page) ?: return emptyList()
        return listOf(day to RectF(0f, 0f, size[0], size[1]))
    }

    private fun drawName(canvas: Canvas, label: String, box: RectF) {
        if (box.width() < 40f || box.height() < 40f) return
        // as big as fits the part, but not huge
        paint.textSize = 1f
        val w = paint.measureText(label).coerceAtLeast(1f)
        paint.textSize = minOf(box.width() * 0.62f / w, box.height() * 0.22f, MAX_TEXT_PX)
        paint.alpha = (255 * OPACITY * strength).toInt()
        val y = box.centerY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(label, box.centerX(), y, paint)
    }

    companion object {
        /** "בשקיפות כמעט מרבית": barely there. */
        const val OPACITY = 0.13f
        const val SHOW_MS = 2500L
        const val FADE_MS = 700L
        const val MAX_TEXT_PX = 260f
    }
}
